package com.whoop5.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import whoop5.Cmd
import whoop5.CommandResponse
import whoop5.Commands
import whoop5.FrameAssembler
import whoop5.RealtimeHR
import whoop5.StandardHR
import whoop5.Whoop
import whoop5.decodeInner
import whoop5.decodeStandardHR
import whoop5.hex
import java.util.UUID

data class FoundDevice(val device: BluetoothDevice, val name: String, val rssi: Int?, val generation: String)

data class UiState(
    val status: String = "Idle",
    val scanning: Boolean = false,
    val devices: List<FoundDevice> = emptyList(),
    val connected: Boolean = false,
    val ready: Boolean = false,
    val deviceName: String? = null,
    val bpm: Int? = null,
    val rrMs: String? = null,
    val hrSource: String? = null,
    val battery: Int? = null,
    val counts: Map<String, Int> = emptyMap(),
    val log: List<String> = emptyList(),
    val logFile: String? = null,
)

/**
 * Owns the GATT connection. Android allows one outstanding GATT operation at a time, so
 * reads, writes and CCCD writes go through [ops] and are run one by one on the main thread.
 * Permissions are checked by the UI before any call here.
 */
@SuppressLint("MissingPermission")
class WhoopBle(private val context: Context) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var gatt: BluetoothGatt? = null
    private val assemblers = mutableMapOf<UUID, FrameAssembler>()
    private var session: SessionLog? = null

    private sealed interface Op {
        data class EnableNotify(val ch: BluetoothGattCharacteristic) : Op
        data class Write(val ch: BluetoothGattCharacteristic, val value: ByteArray, val label: String) : Op
        data class Read(val ch: BluetoothGattCharacteristic) : Op
    }

    private val ops = ArrayDeque<Op>()
    private var busy = false
    private val opTimeout = Runnable { log("! GATT operation timed out"); opDone() }

    // --- scanning ------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids.orEmpty()
            val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
            val gen = when {
                ParcelUuid(Whoop.SERVICE) in uuids -> "5.0/MG"
                ParcelUuid(Whoop.GEN4_SERVICE) in uuids -> "4.0"
                name.contains("whoop", ignoreCase = true) -> "?"
                else -> return
            }
            addDevice(FoundDevice(result.device, name.ifEmpty { result.device.address }, result.rssi, gen))
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(scanning = false, status = "Scan failed ($errorCode)") }
        }
    }

    private fun addDevice(d: FoundDevice) = _state.update { s ->
        s.copy(devices = (s.devices.filterNot { it.device.address == d.device.address } + d)
            .sortedByDescending { it.rssi ?: -200 })
    }

    fun listBonded() {
        adapter?.bondedDevices.orEmpty()
            .filter { it.name?.contains("whoop", ignoreCase = true) == true }
            .forEach { addDevice(FoundDevice(it, it.name, null, "bonded")) }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.update { it.copy(status = "Bluetooth is off") }
            return
        }
        listBonded()
        scanner.startScan(scanCallback)
        _state.update { it.copy(scanning = true, status = "Scanning… (strap in pairing mode for first bond)") }
        main.postDelayed({ stopScan() }, 15_000)
    }

    fun stopScan() {
        if (!_state.value.scanning) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _state.update { it.copy(scanning = false, status = if (it.connected) it.status else "Scan finished") }
    }

    // --- connection ------------------------------------------------------------------

    fun connect(device: BluetoothDevice) {
        stopScan()
        disconnect()
        session = SessionLog.open(context)
        assemblers.clear()
        _state.update {
            it.copy(status = "Connecting to ${device.name ?: device.address}…", deviceName = device.name,
                counts = emptyMap(), log = emptyList(), logFile = session?.file?.absolutePath)
        }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        ops.clear()
        busy = false
        main.removeCallbacks(opTimeout)
        session?.close()
        _state.update { it.copy(connected = false, ready = false, status = "Disconnected") }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val g = gatt ?: return
            if (dev?.address != g.device.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> { log("bonded"); main.post { subscribeAll(g) } }
                BluetoothDevice.BOND_NONE -> status("Bonding failed. Put the strap in pairing mode and retry.")
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(bondReceiver, filter)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _state.update { it.copy(connected = true) }
                    status("Connected, negotiating MTU…")
                    g.requestMtu(517)
                } else {
                    log("disconnected (status $status)")
                    disconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                log("MTU $mtu")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                when {
                    g.getService(Whoop.SERVICE) == null -> status(
                        if (g.getService(Whoop.GEN4_SERVICE) != null) "This is a WHOOP 4.0; this app speaks 5.0/MG"
                        else "WHOOP service not found"
                    )
                    g.device.bondState == BluetoothDevice.BOND_BONDED -> subscribeAll(g)
                    else -> {
                        status("Bonding… accept the pairing prompt if one appears")
                        g.device.createBond()
                    }
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("! subscribe ${Whoop.charName(d.characteristic.uuid)} failed ($status)")
                }
                opDone()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) log("! write failed ($status)")
                opDone()
            }
        }

        @Deprecated("pre-API 33 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") onRead(ch, ch.value?.copyOf() ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onRead(ch, value, status)
        }

        private fun onRead(ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS && ch.uuid == Whoop.BATTERY_LEVEL && value.isNotEmpty()) {
                    _state.update { it.copy(battery = value[0].toInt() and 0xFF) }
                }
                opDone()
            }
        }

        @Deprecated("pre-API 33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = ch.value?.copyOf() ?: return
            main.post { onNotify(ch.uuid, v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { onNotify(ch.uuid, value) }
        }
    }

    private fun subscribeAll(g: BluetoothGatt) {
        if (_state.value.ready) return
        status("Subscribing…")
        for (svc in g.services) for (ch in svc.characteristics) {
            val p = ch.properties
            if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                enqueue(Op.EnableNotify(ch))
            }
        }
        g.getService(Whoop.sig(0x180F))?.getCharacteristic(Whoop.BATTERY_LEVEL)?.let { enqueue(Op.Read(it)) }
        _state.update { it.copy(ready = true, status = "Ready: ${g.device.name ?: g.device.address}") }
        send(Cmd.GET_HELLO)
        send(Cmd.TOGGLE_GENERIC_HR_PROFILE, Commands.toggle(true))
    }

    // --- commands -------------------------------------------------------------------

    fun send(cmd: Cmd, params: ByteArray = ByteArray(0)) = sendRaw(cmd.code, params, cmd.name)

    fun sendRaw(code: Int, params: ByteArray, label: String = Cmd.name(code)) {
        val ch = gatt?.getService(Whoop.SERVICE)?.getCharacteristic(Whoop.CMD_WRITE) ?: run {
            log("! not connected")
            return
        }
        enqueue(Op.Write(ch, Commands.build(code, params), label))
    }

    fun readBattery() {
        gatt?.getService(Whoop.sig(0x180F))?.getCharacteristic(Whoop.BATTERY_LEVEL)?.let { enqueue(Op.Read(it)) }
    }

    private fun enqueue(op: Op) {
        ops.addLast(op)
        if (!busy) next()
    }

    private fun opDone() {
        main.removeCallbacks(opTimeout)
        busy = false
        next()
    }

    @Suppress("DEPRECATION")
    private fun next() {
        val g = gatt ?: return
        val op = ops.removeFirstOrNull() ?: return
        busy = true
        main.postDelayed(opTimeout, 8_000)
        val started = when (op) {
            is Op.EnableNotify -> {
                g.setCharacteristicNotification(op.ch, true)
                val d = op.ch.getDescriptor(Whoop.CCCD)
                if (d == null) { opDone(); return }
                val v = if (op.ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
                else { d.value = v; g.writeDescriptor(d) }
            }
            is Op.Write -> {
                session?.write("tx", op.ch.uuid, op.value)
                log("> ${op.label}  ${op.value.hex()}")
                val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(op.ch, op.value, type) == BluetoothGatt.GATT_SUCCESS
                else { op.ch.writeType = type; op.ch.value = op.value; g.writeCharacteristic(op.ch) }
            }
            is Op.Read -> g.readCharacteristic(op.ch)
        }
        if (!started) {
            log("! could not start $op")
            opDone()
        }
    }

    // --- incoming -------------------------------------------------------------------

    private fun onNotify(uuid: UUID, value: ByteArray) {
        session?.write("rx", uuid, value)
        if (uuid == Whoop.HR_MEASUREMENT) {
            decodeStandardHR(value)?.let { showHr(it) }
            return
        }
        for (frame in assemblers.getOrPut(uuid) { FrameAssembler() }.feed(value)) {
            val key = if (frame.isCommandLike) "${frame.typeName} ${Cmd.name(frame.command)}" else frame.typeName
            _state.update { it.copy(counts = it.counts + (key to (it.counts[key] ?: 0) + 1)) }
            when (val rec = decodeInner(frame)) {
                is RealtimeHR -> if (rec.valid) _state.update {
                    it.copy(bpm = rec.bpm, rrMs = rec.rrMs?.toString(), hrSource = "0x28 stream")
                }
                is CommandResponse -> log("< ${rec.command} seq=${rec.seq} ${rec.body}")
                else -> if (frame.packetType != 0x2F) log("< ${Whoop.charName(uuid)} ${frame.describe()}")
            }
        }
    }

    private fun showHr(hr: StandardHR) = _state.update {
        it.copy(bpm = hr.bpm, rrMs = hr.rrMs.joinToString(", ").ifEmpty { null }, hrSource = "0x2A37")
    }

    private fun status(s: String) {
        log(s)
        _state.update { it.copy(status = s) }
    }

    private fun log(line: String) = _state.update { it.copy(log = (listOf(line) + it.log).take(300)) }
}

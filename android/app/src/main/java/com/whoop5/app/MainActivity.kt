package com.whoop5.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import whoop5.Cmd
import whoop5.Commands
import whoop5.hexToBytes
import java.io.File

class MainActivity : ComponentActivity() {
    private val ble by lazy { (application as WhoopApp).ble }

    private val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var granted by remember { mutableStateOf(hasPermissions()) }
                    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                        granted = hasPermissions()
                    }
                    if (granted) {
                        Screen(ble, ::shareLog)
                    } else {
                        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("WHOOP 5 Lab", style = MaterialTheme.typography.headlineMedium)
                            Text("Bluetooth permission is needed to find and talk to your strap.")
                            Button(onClick = { launcher.launch(permissions) }) { Text("Grant Bluetooth access") }
                        }
                    }
                }
            }
        }
    }

    private fun shareLog(path: String?) {
        val file = path?.let(::File)?.takeIf { it.exists() } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share session log"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Screen(ble: WhoopBle, share: (String?) -> Unit) {
    val s by ble.state.collectAsState()
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text("WHOOP 5 Lab", style = MaterialTheme.typography.headlineMedium)
                Text(s.status, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (!s.connected) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (s.scanning) ble.stopScan() else ble.startScan() }) {
                        Text(if (s.scanning) "Stop scan" else "Scan")
                    }
                    Text(
                        "Close the WHOOP app first: the strap accepts one connection at a time. " +
                            "For the first bond, put the strap in pairing mode.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(s.devices, key = { it.device.address }) { d ->
                Card(Modifier.fillMaxWidth().clickable { ble.connect(d.device) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.name, fontWeight = FontWeight.SemiBold)
                        Text("${d.device.address} · ${d.generation}" + (d.rssi?.let { " · $it dBm" } ?: ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            return@LazyColumn
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.bpm?.toString() ?: "--", fontSize = 56.sp, fontWeight = FontWeight.Bold)
                        Text("bpm" + (s.hrSource?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        s.rrMs?.let { Text("R-R $it ms", style = MaterialTheme.typography.bodySmall) }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(s.battery?.let { "$it%" } ?: "--", fontSize = 24.sp)
                        Text("battery", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ble.send(Cmd.GET_HELLO) }) { Text("Hello") }
                OutlinedButton(onClick = { ble.send(Cmd.REPORT_VERSION_INFO) }) { Text("Version") }
                OutlinedButton(onClick = { ble.send(Cmd.GET_BATTERY_LEVEL); ble.readBattery() }) { Text("Battery") }
                OutlinedButton(onClick = { ble.send(Cmd.GET_CLOCK) }) { Text("Clock") }
                OutlinedButton(onClick = { ble.send(Cmd.GET_DATA_RANGE) }) { Text("Data range") }
                OutlinedButton(onClick = { ble.send(Cmd.TOGGLE_REALTIME_HR, Commands.toggle(true)) }) { Text("Realtime HR") }
                OutlinedButton(onClick = { ble.send(Cmd.TOGGLE_IMU_MODE, Commands.toggleImu(true)) }) { Text("IMU on") }
                OutlinedButton(onClick = { ble.send(Cmd.TOGGLE_IMU_MODE, Commands.toggleImu(false)) }) { Text("IMU off") }
                Button(onClick = { share(s.logFile) }) { Text("Share log") }
                Button(onClick = { ble.disconnect() }) { Text("Disconnect") }
            }
        }

        item { CustomCommand(ble) }

        if (s.counts.isNotEmpty()) {
            item {
                Column {
                    Text("Frames received", style = MaterialTheme.typography.titleSmall)
                    s.counts.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                        Text("%6d  %s".format(v, k), style = mono)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text("Log (newest first)", style = MaterialTheme.typography.titleSmall)
            }
        }
        items(s.log) { Text(it, style = mono) }
    }
}

@Composable
private fun CustomCommand(ble: WhoopBle) {
    var cmd by rememberSaveable { mutableStateOf("") }
    var params by rememberSaveable { mutableStateOf("") }
    var allowWrite by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Custom command", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), label = { Text("name or 0x..") }, singleLine = true)
            OutlinedTextField(params, { params = it }, Modifier.weight(1f), label = { Text("params hex") }, singleLine = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(allowWrite, { allowWrite = it })
            Spacer(Modifier.width(8.dp))
            Text("Allow state-changing commands", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Button(onClick = {
                error = runCatching {
                    val known = Cmd.entries.firstOrNull { it.name.equals(cmd.trim(), ignoreCase = true) }
                    val code = known?.code ?: cmd.trim().removePrefix("0x").toInt(16)
                    require(code in 0..0xFF) { "command must fit in one byte" }
                    if ((known ?: Cmd.of(code))?.mutating == true && !allowWrite) {
                        error("${Cmd.name(code)} changes strap state; enable the switch if you mean it")
                    }
                    ble.sendRaw(code, params.hexToBytes())
                }.exceptionOrNull()?.message
            }) { Text("Send") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

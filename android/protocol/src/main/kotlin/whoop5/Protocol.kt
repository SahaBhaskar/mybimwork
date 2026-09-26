package whoop5

import java.util.UUID
import java.util.zip.CRC32

/**
 * WHOOP 5.0 / MG ("Maverick") wire protocol. Kotlin port of whoop/whoop5/protocol.py;
 * see whoop/docs/PROTOCOL.md for the layout and sources.
 *
 *   [0] 0xAA  [1] 0x01  [2..3] u16 inner+4  [4] 0x00  [5] 0x01  [6..7] CRC16-MODBUS(0..6)
 *   [8..] inner = [packet_type][seq][command][params...]   [..] u32 CRC32(inner)
 */
object Whoop {
    private const val BASE = "-cce1-4033-93ce-002d5875f58a"
    val SERVICE: UUID = UUID.fromString("fd4b0001$BASE")
    val CMD_WRITE: UUID = UUID.fromString("fd4b0002$BASE")
    val CMD_RESPONSE: UUID = UUID.fromString("fd4b0003$BASE")
    val EVENTS: UUID = UUID.fromString("fd4b0004$BASE")
    val DATA: UUID = UUID.fromString("fd4b0005$BASE")
    val MEMFAULT: UUID = UUID.fromString("fd4b0007$BASE")
    val GEN4_SERVICE: UUID = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6")

    val HR_MEASUREMENT: UUID = sig(0x2A37)
    val BATTERY_LEVEL: UUID = sig(0x2A19)
    val CCCD: UUID = sig(0x2902)

    fun sig(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))

    fun charName(uuid: UUID): String = when (uuid) {
        CMD_WRITE -> "cmd_write"
        CMD_RESPONSE -> "cmd_response"
        EVENTS -> "events"
        DATA -> "data"
        MEMFAULT -> "memfault"
        HR_MEASUREMENT -> "heart_rate"
        BATTERY_LEVEL -> "battery"
        else -> uuid.toString()
    }

    const val SOF = 0xAA
    const val HEADER_LEN = 8
    const val TRAILER_LEN = 4
    const val MAX_INNER_LEN = 4096
}

enum class PacketType(val code: Int) {
    COMMAND(0x23), COMMAND_RESPONSE(0x24), PUFFIN_COMMAND(0x25), PUFFIN_COMMAND_RESPONSE(0x26),
    REALTIME_DATA(0x28), REALTIME_RAW_DATA(0x2B), HISTORICAL_DATA(0x2F), EVENT(0x30),
    METADATA(0x31), CONSOLE_LOGS(0x32), REALTIME_IMU(0x33), HISTORICAL_IMU(0x34),
    RELATIVE_PUFFIN_EVENTS(0x35), PUFFIN_EVENTS(0x36), BATTERY_PACK_CONSOLE_LOGS(0x37),
    PUFFIN_METADATA(0x38);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun name(code: Int): String = byCode[code]?.name ?: "0x%02X".format(code)
    }
}

enum class Cmd(val code: Int, val mutating: Boolean = false) {
    LINK_VALID(0x01), GET_MAX_PROTOCOL_VERSION(0x02), TOGGLE_REALTIME_HR(0x03),
    REPORT_VERSION_INFO(0x07), SET_CLOCK(0x0A, true), GET_CLOCK(0x0B),
    TOGGLE_GENERIC_HR_PROFILE(0x0E), RUN_HAPTIC_PATTERN_MAVERICK(0x13),
    ABORT_HISTORICAL_TRANSMITS(0x14, true), SEND_HISTORICAL_DATA(0x16),
    HISTORICAL_DATA_RESULT(0x17, true), GET_BATTERY_LEVEL(0x1A), REBOOT_STRAP(0x1D, true),
    GET_DATA_RANGE(0x22), SEND_R10_R11_REALTIME(0x3F), SET_ALARM_TIME(0x42, true),
    GET_ALARM_TIME(0x43), RUN_ALARM(0x44), DISABLE_ALARM(0x45, true), START_RAW_DATA(0x51),
    STOP_RAW_DATA(0x52), GET_BODY_LOCATION_AND_STATUS(0x54), ENTER_HIGH_FREQ_SYNC(0x60),
    EXIT_HIGH_FREQ_SYNC(0x61), GET_EXTENDED_BATTERY_INFO(0x62),
    TOGGLE_IMU_MODE_HISTORICAL(0x69), TOGGLE_IMU_MODE(0x6A), ENABLE_OPTICAL_DATA(0x6B),
    TOGGLE_OPTICAL_MODE(0x6C), SET_FF_VALUE(0x78, true), STOP_HAPTICS(0x7A),
    SELECT_WRIST(0x7B, true), GET_FF_VALUE(0x80), GET_ADVERTISING_NAME(0x8D), GET_HELLO(0x91),
    GET_BATTERY_PACK_INFO(0x97);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: Int): Cmd? = byCode[code]
        fun name(code: Int): String = byCode[code]?.name ?: "0x%02X".format(code)
    }
}

object Crc {
    fun crc16Modbus(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value
}

class FrameException(msg: String) : Exception(msg)

class Frame(val inner: ByteArray, val roleA: Int = 0x00, val roleB: Int = 0x01) {
    val packetType: Int get() = inner.u8(0)
    val seq: Int get() = if (inner.size > 1) inner.u8(1) else 0
    val command: Int get() = if (inner.size > 2) inner.u8(2) else 0
    val params: ByteArray get() = inner.copyOfRange(minOf(3, inner.size), inner.size)
    val typeName: String get() = PacketType.name(packetType)

    val isCommandLike: Boolean
        get() = packetType in 0x23..0x26

    fun encode(): ByteArray = encodeFrame(inner, roleA, roleB)

    fun describe(): String = buildString {
        append("$typeName seq=$seq")
        if (isCommandLike) append(" cmd=${Cmd.name(command)}")
        append(" [${inner.size}B] ${inner.hex()}")
    }
}

fun encodeFrame(inner: ByteArray, roleA: Int = 0x00, roleB: Int = 0x01): ByteArray {
    val len = inner.size + Whoop.TRAILER_LEN
    val header = byteArrayOf(Whoop.SOF.toByte(), 0x01, len.toByte(), (len ushr 8).toByte(),
        roleA.toByte(), roleB.toByte())
    return header + le16(Crc.crc16Modbus(header)) + inner + le32(Crc.crc32(inner))
}

fun decodeFrame(data: ByteArray): Frame {
    if (data.size < Whoop.HEADER_LEN + Whoop.TRAILER_LEN) throw FrameException("too short (${data.size} bytes)")
    if (data.u8(0) != Whoop.SOF) throw FrameException("bad SOF")
    if (data.u16(6) != Crc.crc16Modbus(data, 0, 6)) throw FrameException("header CRC16 mismatch")
    val len = data.u16(2)
    if (data.size != Whoop.HEADER_LEN + len) throw FrameException("length mismatch")
    val inner = data.copyOfRange(Whoop.HEADER_LEN, Whoop.HEADER_LEN + len - Whoop.TRAILER_LEN)
    if (data.u32(Whoop.HEADER_LEN + len - Whoop.TRAILER_LEN) != Crc.crc32(inner)) {
        throw FrameException("payload CRC32 mismatch")
    }
    if (inner.isEmpty()) throw FrameException("empty inner packet")
    return Frame(inner, data.u8(4), data.u8(5))
}

/** Reassembles frames split across BLE notifications; drops bytes that can't start a frame. */
class FrameAssembler {
    private var buf = ByteArray(0)
    var dropped = 0L
        private set

    fun feed(chunk: ByteArray): List<Frame> {
        buf += chunk
        val out = mutableListOf<Frame>()
        while (true) {
            val start = buf.indexOfFirst { it.toInt() and 0xFF == Whoop.SOF }
            if (start < 0) {
                dropped += buf.size
                buf = ByteArray(0)
                return out
            }
            if (start > 0) {
                dropped += start
                buf = buf.copyOfRange(start, buf.size)
            }
            if (buf.size < Whoop.HEADER_LEN) return out
            val len = buf.u16(2)
            if (buf.u16(6) != Crc.crc16Modbus(buf, 0, 6) || len <= Whoop.TRAILER_LEN || len > Whoop.MAX_INNER_LEN) {
                skipOne()
                continue
            }
            val total = Whoop.HEADER_LEN + len
            if (buf.size < total) return out
            try {
                out += decodeFrame(buf.copyOfRange(0, total))
                buf = buf.copyOfRange(total, buf.size)
            } catch (e: FrameException) {
                skipOne()
            }
        }
    }

    private fun skipOne() {
        dropped++
        buf = buf.copyOfRange(1, buf.size)
    }
}

object Commands {
    private var nextSeq = 1

    /** Encode a command frame for Whoop.CMD_WRITE. Inner packet is zero-padded to a multiple of 4. */
    fun build(cmd: Int, params: ByteArray = ByteArray(0), seq: Int? = null,
              packetType: Int = PacketType.COMMAND.code): ByteArray {
        val s = seq ?: synchronized(this) { nextSeq.also { nextSeq = (nextSeq + 1) and 0xFF } }
        var inner = byteArrayOf(packetType.toByte(), s.toByte(), cmd.toByte()) + params
        inner += ByteArray((4 - inner.size % 4) % 4)
        return encodeFrame(inner)
    }

    fun build(cmd: Cmd, params: ByteArray = ByteArray(0), seq: Int? = null) = build(cmd.code, params, seq)

    fun toggle(enabled: Boolean) = byteArrayOf(if (enabled) 1 else 0)
    fun toggleImu(enabled: Boolean) = byteArrayOf(0x01, if (enabled) 1 else 0)
}

// --- byte helpers -------------------------------------------------------------

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.u32(i: Int): Long = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
internal fun ByteArray.f32(i: Int): Float = java.lang.Float.intBitsToFloat(u32(i).toInt())
internal fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
internal fun le32(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val s = filterNot { it.isWhitespace() }
    require(s.length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

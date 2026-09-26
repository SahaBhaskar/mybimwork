package whoop5

/** Known payload layouts; offsets are into the inner packet. Mirrors whoop/whoop5/decoders.py. */
sealed interface Record

data class RealtimeHR(val unixTs: Long, val bpm: Int, val valid: Boolean, val rrMs: Int?) : Record

data class HistoricalHR(
    val sequence: Int, val bpm: Int, val flag: Int, val rrMs: Int?, val smoothedBpm: Int,
    val quaternion: List<Float>,
) : Record

data class CommandResponse(val command: String, val seq: Int, val body: String) : Record

data class GenericRecord(val packetType: String, val recordType: Int?, val length: Int, val hex: String) : Record

data class StandardHR(val bpm: Int, val contact: Boolean?, val rrMs: List<Double>) : Record

fun decodeInner(frame: Frame): Record {
    val p = frame.inner
    return when {
        frame.packetType == PacketType.REALTIME_DATA.code && p.size >= 12 && p.u8(1) == 0x02 -> {
            val valid = p.u8(9) == 0x01
            val rr = p.u16(10)
            RealtimeHR(p.u32(2), p.u8(8), valid, if (valid && rr != 0) rr else null)
        }
        frame.packetType == PacketType.HISTORICAL_DATA.code && p.size >= 49 && p.u8(1) == 18 -> {
            val flag = p.u8(15)
            val rr = p.u16(16)
            HistoricalHR(p.u16(2), p.u8(14), flag, if (flag != 0 && rr != 0) rr else null, p.u8(29),
                List(4) { p.f32(33 + it * 4) })
        }
        frame.packetType == PacketType.COMMAND_RESPONSE.code ||
            frame.packetType == PacketType.PUFFIN_COMMAND_RESPONSE.code ->
            CommandResponse(Cmd.name(frame.command), frame.seq, frame.params.hex())
        else -> GenericRecord(frame.typeName, if (p.size > 1) p.u8(1) else null, p.size, p.hex())
    }
}

/** Bluetooth SIG Heart Rate Measurement (0x2A37). */
fun decodeStandardHR(data: ByteArray): StandardHR? {
    if (data.size < 2) return null
    val flags = data.u8(0)
    var i: Int
    val bpm: Int
    if (flags and 0x01 != 0) {
        if (data.size < 3) return null
        bpm = data.u16(1); i = 3
    } else {
        bpm = data.u8(1); i = 2
    }
    val contact = if (flags and 0x04 != 0) flags and 0x02 != 0 else null
    if (flags and 0x08 != 0) i += 2
    val rr = mutableListOf<Double>()
    if (flags and 0x10 != 0) {
        while (i + 1 < data.size) {
            rr += Math.round(data.u16(i) * 10000.0 / 1024) / 10.0
            i += 2
        }
    }
    return StandardHR(bpm, contact, rr)
}

package whoop5

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProtocolTest {
    // Publicly documented TOGGLE_IMU_MODE frame (whoop/docs/PROTOCOL.md); same vector as the Python tests.
    private val known = "aa010c000001e741 23f16a0101000000 58e961fc".hexToBytes()

    @Test fun crcsMatchKnownFrame() {
        assertEquals(0x41E7, Crc.crc16Modbus(known, 0, 6))
        assertEquals(0xFC61E958L, Crc.crc32(known.copyOfRange(8, 16)))
    }

    @Test fun buildReproducesKnownFrame() {
        assertContentEquals(known, Commands.build(Cmd.TOGGLE_IMU_MODE, Commands.toggleImu(true), seq = 0xF1))
    }

    @Test fun decodeRoundTrip() {
        val f = decodeFrame(known)
        assertEquals(0x23, f.packetType)
        assertEquals(0xF1, f.seq)
        assertEquals(Cmd.TOGGLE_IMU_MODE.code, f.command)
        assertContentEquals(known, f.encode())
    }

    @Test fun decodeRejectsCorruption() {
        for (i in listOf(0, 7, 9, 19)) {
            val bad = known.copyOf().also { it[i] = (it[i].toInt() xor 0x40).toByte() }
            assertFailsWith<FrameException> { decodeFrame(bad) }
        }
    }

    @Test fun commandsArePaddedToFour() {
        assertContentEquals("23019100".hexToBytes(), decodeFrame(Commands.build(Cmd.GET_HELLO, seq = 1)).inner)
    }

    @Test fun assemblerHandlesFragmentsAndGarbage() {
        val stream = "00aa13".hexToBytes() + Commands.build(Cmd.LINK_VALID, seq = 1) +
            Commands.build(Cmd.GET_BATTERY_LEVEL, seq = 2)
        val asm = FrameAssembler()
        val frames = stream.toList().chunked(5).flatMap { asm.feed(it.toByteArray()) }
        assertEquals(listOf(0x01, 0x1A), frames.map { it.command })
        assertEquals(3L, asm.dropped)
    }

    @Test fun realtimeHR() {
        val inner = byteArrayOf(0x28, 0x02) + le32(1_750_000_000L) + byteArrayOf(0, 0, 61, 1) +
            le16(983) + ByteArray(6) + byteArrayOf(1, 0)
        assertEquals(RealtimeHR(1_750_000_000L, 61, true, 983), decodeInner(Frame(inner)))
    }

    @Test fun historicalHR() {
        val inner = ByteArray(116)
        inner[0] = 0x2F; inner[1] = 18
        le16(77).copyInto(inner, 2)
        inner[14] = 55; inner[15] = 1
        le16(1090).copyInto(inner, 16)
        inner[29] = 53
        le32(java.lang.Float.floatToIntBits(1.0f).toLong()).copyInto(inner, 33)
        val rec = assertIs<HistoricalHR>(decodeInner(Frame(inner)))
        assertEquals(listOf(77, 55, 1090, 53), listOf(rec.sequence, rec.bpm, rec.rrMs, rec.smoothedBpm))
        assertEquals(listOf(1f, 0f, 0f, 0f), rec.quaternion)
    }

    @Test fun commandResponse() {
        assertEquals(CommandResponse("GET_BATTERY_LEVEL", 5, "0155"),
            decodeInner(Frame("24051a0155".hexToBytes())))
    }

    @Test fun standardHR() {
        val rec = decodeStandardHR(byteArrayOf(0x16, 72) + le16(1024))!!
        assertEquals(StandardHR(72, true, listOf(1000.0)), rec)
    }
}

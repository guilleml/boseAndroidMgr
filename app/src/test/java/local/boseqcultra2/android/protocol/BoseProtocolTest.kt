// Pruebas con los mensajes literales del brief y de BoseMenuBar (docs/protocol.md, BoseKitTests).
package local.boseqcultra2.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoseProtocolTest {
    private fun hex(s: String) = Hex.parse(s)

    /** Interpreta una cadena hex como un único mensaje completo. */
    private fun msg(s: String): BoseMessage {
        val parsed = MessageParser().feed(hex(s))
        assertEquals("se esperaba un único mensaje en: $s", 1, parsed.size)
        return parsed[0]
    }

    @Test fun commandsMatchCapture() {
        assertArrayEquals(hex("1f 03 01 00"), BoseCommand.getCurrentMode.toBytes())
        assertArrayEquals(hex("1f 06 05 00"), BoseCommand.listModes.toBytes())
        assertArrayEquals(hex("02 01 05 00"), BoseCommand.dumpBlock02.toBytes())
        assertArrayEquals(hex("01 01 05 00"), BoseCommand.dumpBlock01.toBytes())
        assertArrayEquals(hex("1f 03 05 02 02 01"), BoseCommand.setMode(0x02).toBytes())
        assertArrayEquals(hex("01 07 02 02 02 00"), BoseCommand.setEqualizer(band = 0, value = 2).toBytes())
        assertArrayEquals(hex("01 07 02 02 ff 02"), BoseCommand.setEqualizer(band = 2, value = -1).toBytes())
    }

    @Test fun parserSplitsConcatenatedAndPartialMessages() {
        // CONSTRUIDO (no literal): varios mensajes en una lectura, cortada en dos trozos.
        val all = hex("05 01 01 00 04 04 01 00 12 0b 01 00 1f 03 03 01 02")
        val p = MessageParser()
        assertEquals(2, p.feed(all.copyOfRange(0, 10)).size)
        assertEquals(2, p.pendingBytes)
        val rest = p.feed(all.copyOfRange(10, all.size))
        assertEquals(2, rest.size)
        assertEquals(BoseMessage(0x1f, 0x03, 0x03, byteArrayOf(0x02)), rest.last())
        assertEquals(0, p.pendingBytes)
    }

    @Test fun parserHandlesByteByByteAndLengthArgument() {
        val all = hex("1f 03 03 01 00 1f 06 06 00")
        val p = MessageParser()
        val out = all.flatMap { p.feed(byteArrayOf(it)) }
        assertEquals(listOf(BoseMessage(0x1f, 0x03, 0x03, byteArrayOf(0)), BoseMessage(0x1f, 0x06, 0x06)), out)
        // Solo se usan los primeros `length` bytes del búfer de lectura (como InputStream.read).
        val buf = hex("1f 03 03 01 00 99 99")
        assertEquals(1, MessageParser().feed(buf, 5).size)
    }

    @Test fun currentMode() {
        assertEquals(0x00, BoseDecode.currentMode(msg("1f 03 03 01 00")))
        assertNull(BoseDecode.currentMode(msg("1f 03 06 01 03")))
    }

    @Test fun modeChangeAck() {
        assertEquals(0x02, BoseDecode.modeChangeAck(msg("1f 03 06 01 02")))
        assertEquals(0x02, BoseDecode.modeChangeAck(msg("1f 03 03 01 02")))
        assertNull(BoseDecode.modeChangeAck(msg("1f 06 06 00")))
    }

    @Test fun modeSlotLiteralAware() {
        val m = msg(
            "1f 06 03 30 01 00 02 00 00 01 41 77 61 72 65 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 02 0a 01 00 00 00 01",
        )
        assertEquals(0x30, m.payload.size)
        assertEquals(ModeSlot(0x01, "Aware"), BoseDecode.modeSlot(m))
    }

    @Test fun modeSlotNoneIsDiscarded() {
        val payload = hex("07 00 00 01 00 00") + "None".toByteArray() + ByteArray(0x30 - 10)
        assertNull(BoseDecode.modeSlot(BoseMessage(0x1f, 0x06, 0x03, payload)))
    }

    @Test fun modeListMarkers() {
        assertTrue(BoseDecode.isModeListStart(msg("1f 06 07 00")))
        assertTrue(BoseDecode.isModeListEnd(msg("1f 06 06 00")))
    }

    @Test fun batteryFourEntries() {
        assertEquals(
            listOf(BatteryEntry(1, 100), BatteryEntry(2, 100), BatteryEntry(4, 100), BatteryEntry(3, 90)),
            BoseDecode.battery(msg("02 02 03 10 64 ff ff 01 64 ff ff 02 64 ff ff 04 5a ff ff 03")),
        )
    }

    @Test fun batteryWithoutLeft() {
        assertEquals(
            listOf(BatteryEntry(1, 100), BatteryEntry(4, 100), BatteryEntry(3, 90)),
            BoseDecode.battery(msg("02 02 03 0c 64 ff ff 01 64 ff ff 04 5a ff ff 03")),
        )
    }

    @Test fun equalizerSignedValues() {
        // Respuesta a `01 07 02 02 ff 02` (Agudos = −1).
        assertEquals(
            listOf(EqBand(0, 3, -10, 10), EqBand(1, 2, -10, 10), EqBand(2, -1, -10, 10)),
            BoseDecode.equalizer(msg("01 07 03 0c f6 0a 03 00 f6 0a 02 01 f6 0a ff 02")),
        )
    }

    @Test fun decodersRejectOtherMessages() {
        val other = msg("1f 03 03 01 00")
        assertNull(BoseDecode.battery(other))
        assertNull(BoseDecode.equalizer(other))
        assertNull(BoseDecode.modeSlot(other))
    }

    @Test fun toStringIsHex() {
        assertEquals("1f 03 05 02 | 02 01", BoseCommand.setMode(2).toString())
        assertEquals("1f 03 01 00", BoseCommand.getCurrentMode.toString())
    }
}

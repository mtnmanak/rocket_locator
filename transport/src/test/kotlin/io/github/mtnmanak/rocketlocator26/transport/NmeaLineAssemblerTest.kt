package io.github.mtnmanak.rocketlocator26.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NmeaLineAssemblerTest {

    // Known-good canonical NMEA fixtures (checksums verified).
    private val gga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    private val rmc = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    // ---------------------------------------------------------------- basics

    @Test
    fun `single complete line in one feed`() {
        val assembler = NmeaLineAssembler()

        assertEquals(listOf(gga), assembler.feed(bytes("$gga\r\n")))
    }

    @Test
    fun `multiple lines in one chunk`() {
        val assembler = NmeaLineAssembler()

        val lines = assembler.feed(bytes("$gga\r\n$rmc\r\n$gga\r\n"))

        assertEquals(listOf(gga, rmc, gga), lines)
    }

    @Test
    fun `bare LF terminates a line like CRLF does`() {
        val assembler = NmeaLineAssembler()

        val lines = assembler.feed(bytes("$gga\n$rmc\r\n"))

        assertEquals(listOf(gga, rmc), lines)
    }

    @Test
    fun `blank lines are not emitted`() {
        val assembler = NmeaLineAssembler()

        val lines = assembler.feed(bytes("\r\n\n\r\n$gga\r\n\r\n"))

        assertEquals(listOf(gga), lines)
    }

    @Test
    fun `empty feed returns no lines`() {
        val assembler = NmeaLineAssembler()

        assertTrue(assembler.feed(ByteArray(0)).isEmpty())
    }

    // ---------------------------------------------------------------- fragmentation

    @Test
    fun `line split across many one-byte feeds`() {
        val assembler = NmeaLineAssembler()
        val stream = bytes("$gga\r\n")
        val lines = mutableListOf<String>()

        for (byte in stream) {
            lines += assembler.feed(byteArrayOf(byte))
        }

        assertEquals(listOf(gga), lines)
    }

    @Test
    fun `twenty-byte BLE fragments reassemble a GGA sentence`() {
        val assembler = NmeaLineAssembler()
        val stream = bytes("$gga\r\n$rmc\r\n")
        val lines = mutableListOf<String>()

        var offset = 0
        while (offset < stream.size) {
            val end = minOf(offset + 20, stream.size)
            lines += assembler.feed(stream.copyOfRange(offset, end))
            offset = end
        }

        assertEquals(listOf(gga, rmc), lines)
    }

    @Test
    fun `partial line is held across feeds until its terminator arrives`() {
        val assembler = NmeaLineAssembler()

        assertTrue(assembler.feed(bytes("\$GPGGA,123519,4807")).isEmpty())
        assertTrue(assembler.feed(bytes(".038,N,01131.000,E,1,08,")).isEmpty())

        val lines = assembler.feed(bytes("0.9,545.4,M,46.9,M,,*47\r\n"))

        assertEquals(listOf(gga), lines)
    }

    // ---------------------------------------------------------------- robustness

    @Test
    fun `overlong garbage is discarded and assembly recovers on the next line`() {
        // 82 = NMEA 0183 max sentence length; the 65-char GGA fixture must fit.
        val assembler = NmeaLineAssembler(maxLineLength = 82)
        val garbage = ByteArray(500) { 'x'.code.toByte() }

        assertTrue(assembler.feed(garbage).isEmpty())
        // The newline ends the garbage run without emitting it; the valid
        // sentence that follows is assembled normally.
        val lines = assembler.feed(bytes("\n$gga\r\n"))

        assertEquals(listOf(gga), lines)
    }

    @Test
    fun `endless unterminated noise never emits and stays bounded`() {
        val assembler = NmeaLineAssembler(maxLineLength = 32)
        val noise = ByteArray(1_000) { (it % 251).toByte() }.map { b ->
            if (b == '\n'.code.toByte()) 'x'.code.toByte() else b
        }.toByteArray()

        repeat(100) {
            assertTrue(assembler.feed(noise).isEmpty())
        }
    }

    @Test
    fun `line of exactly maxLineLength bytes is emitted intact`() {
        val line = "A".repeat(16)
        val assembler = NmeaLineAssembler(maxLineLength = 16)

        assertEquals(listOf(line), assembler.feed(bytes("$line\n")))
    }

    @Test
    fun `high-bit bytes decode via ISO-8859-1 without throwing`() {
        val assembler = NmeaLineAssembler()
        val input = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(), 'A'.code.toByte(), '\n'.code.toByte(),
        )

        val lines = assembler.feed(input)

        // 0xFF and 0xFE map to U+00FF / U+00FE under ISO-8859-1.
        assertEquals(listOf("ÿþA"), lines)
    }

    // ---------------------------------------------------------------- count parameter

    @Test
    fun `count limits how much of the buffer is consumed`() {
        val assembler = NmeaLineAssembler()
        val buffer = bytes("AB\nCD\n")

        val lines = assembler.feed(buffer, count = 3)

        assertEquals(listOf("AB"), lines)
        // The trailing "CD\n" was outside count and must not have been buffered.
        assertEquals(listOf("EF"), assembler.feed(bytes("EF\n")))
    }

    @Test
    fun `count of zero consumes nothing`() {
        val assembler = NmeaLineAssembler()

        assertTrue(assembler.feed(bytes("$gga\r\n"), count = 0).isEmpty())
        assertEquals(listOf(gga), assembler.feed(bytes("$gga\r\n")))
    }
}

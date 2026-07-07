package io.github.mtnmanak.rocketlocator26.core.sim

import io.github.mtnmanak.rocketlocator26.core.nmea.GgaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParseResult
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParser
import io.github.mtnmanak.rocketlocator26.core.nmea.RmcSentence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NmeaLogReplayTest {

    private val parser = NmeaParser()

    // Known-good canonical fixtures (checksums verified).
    private val gga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    private val rmc = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"

    @Test
    fun emitsOneLinePerSampleAtDefaultCadence() {
        val samples = NmeaLogReplay(listOf(gga, rmc)).samples().toList()
        assertEquals(2, samples.size)
        assertEquals(SimulatedSample(0L, listOf(gga)), samples[0])
        assertEquals(SimulatedSample(1000L, listOf(rmc)), samples[1])
    }

    @Test
    fun skipsBlankLinesWithoutConsumingTimeSlots() {
        val lines = listOf("", gga, "   ", "\t", rmc, "", "  \r\n")
        val samples = NmeaLogReplay(lines).samples().toList()
        assertEquals(2, samples.size)
        assertEquals(0L, samples[0].timeOffsetMs)
        assertEquals(listOf(gga), samples[0].sentences)
        assertEquals(1000L, samples[1].timeOffsetMs)
        assertEquals(listOf(rmc), samples[1].sentences)
    }

    @Test
    fun pacesAtCustomInterval() {
        val samples = NmeaLogReplay(listOf(gga, rmc, gga), intervalMs = 250).samples().toList()
        assertEquals(listOf(0L, 250L, 500L), samples.map { it.timeOffsetMs })
        for (i in 1 until samples.size) {
            assertTrue(
                samples[i].timeOffsetMs > samples[i - 1].timeOffsetMs,
                "timeOffsetMs not strictly increasing at $i",
            )
        }
    }

    @Test
    fun trimsCarriageReturnsAndSurroundingWhitespace() {
        val lines = listOf("$gga\r", "  $rmc  \r\n")
        val samples = NmeaLogReplay(lines).samples().toList()
        assertEquals(2, samples.size)
        assertEquals(gga, samples[0].sentences.single())
        assertEquals(rmc, samples[1].sentences.single())
        // Trimmed lines must still round-trip through the real parser.
        for (sample in samples) {
            val result = parser.parse(sample.sentences.single())
            assertTrue(result is NmeaParseResult.Parsed, "expected Parsed, got $result")
        }
    }

    @Test
    fun emptyInputYieldsEmptySequence() {
        assertEquals(0, NmeaLogReplay(emptyList()).samples().count())
    }

    @Test
    fun allBlankInputYieldsEmptySequence() {
        assertEquals(0, NmeaLogReplay(listOf("", "   ", "\r\n", "\t")).samples().count())
    }

    @Test
    fun replayedFixturesDecodeThroughRealParser() {
        val samples = NmeaLogReplay(listOf(gga, rmc)).samples().toList()

        val first = parser.parse(samples[0].sentences.single())
        assertTrue(first is NmeaParseResult.Parsed, "GGA fixture failed to parse: $first")
        val ggaSentence = (first as NmeaParseResult.Parsed).sentence as GgaSentence
        assertEquals(48.1173, ggaSentence.latitude!!, 1e-4)
        assertEquals(11.5167, ggaSentence.longitude!!, 1e-4)
        assertEquals(545.4, ggaSentence.altitudeMslMeters!!, 1e-9)
        assertEquals(1, ggaSentence.fixQuality)

        val second = parser.parse(samples[1].sentences.single())
        assertTrue(second is NmeaParseResult.Parsed, "RMC fixture failed to parse: $second")
        val rmcSentence = (second as NmeaParseResult.Parsed).sentence as RmcSentence
        assertTrue(rmcSentence.isValid)
        assertEquals(22.4, rmcSentence.speedKnots!!, 1e-9)
        assertEquals(84.4, rmcSentence.courseDeg!!, 1e-9)
        assertEquals("230394", rmcSentence.date)
    }

    @Test
    fun garbageLinesPassThroughUnvalidated() {
        // Replay must not filter or "fix" bad lines — parser tolerance is
        // exercised downstream, exactly as with live hardware.
        val garbage = "not nmea at all"
        val samples = NmeaLogReplay(listOf(garbage, gga)).samples().toList()
        assertEquals(2, samples.size)
        assertEquals(garbage, samples[0].sentences.single())
        assertTrue(parser.parse(samples[0].sentences.single()) is NmeaParseResult.Failed)
        assertTrue(parser.parse(samples[1].sentences.single()) is NmeaParseResult.Parsed)
    }

    @Test
    fun sequenceIsDeterministicAndReplayable() {
        val replay = NmeaLogReplay(listOf(gga, "", rmc), intervalMs = 100)
        assertEquals(replay.samples().toList(), replay.samples().toList())
    }

    @Test
    fun rejectsNonPositiveInterval() {
        assertThrows(IllegalArgumentException::class.java) {
            NmeaLogReplay(listOf(gga), intervalMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NmeaLogReplay(listOf(gga), intervalMs = -1000)
        }
    }
}

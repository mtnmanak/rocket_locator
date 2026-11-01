package io.github.mtnmanak.rocketlocator26.core.sim

import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import io.github.mtnmanak.rocketlocator26.core.geo.Geodesy
import io.github.mtnmanak.rocketlocator26.core.nmea.GgaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParseResult
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParser
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.RmcSentence
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SyntheticFlightBoundaryTest {
    private val parser = NmeaParser()

    @Test
    fun nearPoleFlightGeneratesFiniteCoordinates() {
        val params =
            FlightParams(
                launch = GeoPoint(89.99171478, 0.0),
                launchAltitudeMslMeters = 0.0,
                apogeeAglMeters = 100.0,
                ascentSeconds = 1.0,
                descentSeconds = 1.0,
                driftBearingDeg = 0.0,
                driftDistanceMeters = 921.2757026530004,
                sampleHz = 1,
            )
        val samples =
            assertDoesNotThrow<List<SimulatedSample>> {
                SyntheticFlight(params).samples().toList()
            }
        assertEquals(17, samples.size)
        for (sample in samples) {
            assertEquals(2, sample.sentences.size)
            for (raw in sample.sentences) {
                val (latitude, longitude) =
                    when (val sentence = parsed(raw)) {
                        is GgaSentence -> sentence.latitude to sentence.longitude
                        is RmcSentence -> sentence.latitude to sentence.longitude
                        else -> error("Unexpected simulated sentence: $sentence")
                    }
                assertTrue(latitude?.isFinite() == true, "Non-finite/missing latitude: $raw")
                assertTrue(longitude?.isFinite() == true, "Non-finite/missing longitude: $raw")
            }
        }
    }

    @ParameterizedTest
    @CsvSource("3.6, 1, 4", "3.6, 2, 7", "0.1, 1, 1")
    fun descentSpeedMatchesMovementOverRoundedSampleDuration(
        descentSeconds: Double,
        sampleHz: Int,
        descentCount: Int,
    ) {
        val params =
            FlightParams(
                GeoPoint(40.0, -105.0),
                1500.0,
                100.0,
                1.0,
                descentSeconds,
                90.0,
                400.0,
                sampleHz,
            )
        val samples = SyntheticFlight(params).samples().toList()
        val descentStart = 5 + sampleHz // five pad samples plus one second of ascent
        assertEquals(descentStart + descentCount + 10, samples.size)
        val start = samples[descentStart - 1]
        val end = samples[descentStart + descentCount - 1]
        val durationSeconds = (end.timeOffsetMs - start.timeOffsetMs) / 1000.0
        assertEquals(descentCount.toDouble() / sampleHz, durationSeconds, 1e-9)
        val distance = Geodesy.distanceMeters(position(start), position(end))
        assertEquals(400.0, distance, 0.4) // NMEA coordinate quantization
        val expectedKnots = 400.0 / durationSeconds * 3600.0 / 1852.0
        for (sample in samples.subList(descentStart, descentStart + descentCount)) {
            val rmc = parsed(sample.sentences[1]) as RmcSentence
            assertEquals(expectedKnots, rmc.speedKnots!!, 0.06) // 0.1 knot encoding resolution
        }
    }

    private fun position(sample: SimulatedSample): GeoPoint {
        val gga = parsed(sample.sentences[0]) as GgaSentence
        return GeoPoint(gga.latitude!!, gga.longitude!!)
    }

    private fun parsed(raw: String): NmeaSentence {
        val result = parser.parse(raw)
        assertTrue(result is NmeaParseResult.Parsed, "Round-trip failed: $result for $raw")
        return (result as NmeaParseResult.Parsed).sentence
    }
}

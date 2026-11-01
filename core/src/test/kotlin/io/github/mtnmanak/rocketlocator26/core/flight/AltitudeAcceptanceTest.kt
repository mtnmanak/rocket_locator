package io.github.mtnmanak.rocketlocator26.core.flight

import io.github.mtnmanak.rocketlocator26.core.nmea.GgaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParseResult
import io.github.mtnmanak.rocketlocator26.core.nmea.RmcSentence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AltitudeAcceptanceTest {
    @ParameterizedTest
    @ValueSource(doubles = [-1000.0, 120000.0])
    fun hardAltitudeBoundsAreInclusive(altitude: Double) {
        val after = FlightTracker().onResult(gga(altitude), 1_000L)
        assertEquals(altitude, after.altitudeMslMeters)
        assertEquals(altitude, after.maxAltitudeMslMeters)
        assertEquals(0.0, after.altitudeAglMeters)
    }

    @ParameterizedTest
    @ValueSource(doubles = [-1000.1, 120000.1])
    fun outOfBoundsFirstAltitudeDoesNotEstablishBaseline(altitude: Double) {
        assertRejectedFirstAltitude(altitude)
    }

    @Test
    fun nonFiniteFirstAltitudesAreIgnored() {
        for (altitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertRejectedFirstAltitude(altitude)
        }
    }

    @Test
    fun rateGateUsesElapsedReceiptTimeAndIncludesToleranceBoundary() {
        for ((elapsed, maximumDelta) in listOf(1_000L to 1600.0, 2_000L to 3100.0)) {
            val accepted = seededTracker().onResult(gga(1500.0 + maximumDelta), 1_000L + elapsed)
            assertEquals(1500.0 + maximumDelta, accepted.altitudeMslMeters)
            val rejected = seededTracker().onResult(gga(1500.1 + maximumDelta), 1_000L + elapsed)
            assertEquals(1500.0, rejected.altitudeMslMeters)
            assertEquals(1500.0, rejected.maxAltitudeMslMeters)
            assertEquals(1_000L + elapsed, rejected.lastFixAtMs)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, 1000L, 1500L])
    fun shortOrBackwardReceiptIntervalsUseOneSecondFloor(nowMs: Long) {
        val accepted = seededTracker().onResult(gga(3100.0), nowMs)
        assertEquals(3100.0, accepted.altitudeMslMeters)
        val rejected = seededTracker().onResult(gga(3100.1), nowMs)
        assertEquals(1500.0, rejected.altitudeMslMeters)
    }

    @Test
    fun descentUsesTheSameAbsoluteRateGate() {
        val tracker = FlightTracker()
        tracker.onResult(gga(5000.0), 1_000L)
        assertEquals(5000.0, tracker.onResult(gga(3399.9), 2_000L).altitudeMslMeters)
        assertEquals(3400.0, tracker.onResult(gga(3400.0), 2_000L).altitudeMslMeters)
    }

    @Test
    fun rejectedMissingAndUnmeasuredAltitudesDoNotAdvanceRateGate() {
        for (intervening in listOf(gga(99999.9), gga(null), gga(9000.0, quality = 6))) {
            val tracker = seededTracker()
            tracker.onResult(intervening, 5_000L)
            // 7400 m in 5 s is allowed. Using the rejected sample's time would reject it.
            val after = tracker.onResult(gga(8900.0), 6_000L)
            assertEquals(8900.0, after.altitudeMslMeters)
            assertEquals(7400.0, after.altitudeAglMeters)
        }
    }

    @Test
    fun horizontalFixDoesNotAdvanceAltitudeRateClock() {
        val tracker = seededTracker()
        tracker.onResult(NmeaParseResult.Parsed(
            RmcSentence("GP", "120000", true, 41.0, -106.0, 0.0, 0.0, null), "raw",
        ), 5_000L)
        val after = tracker.onResult(gga(8900.0), 6_000L)
        assertEquals(8900.0, after.altitudeMslMeters)
    }

    @Test
    fun fullResetClearsAltitudeRateHistory() {
        val tracker = FlightTracker()
        tracker.onResult(gga(120000.0), 1_000L)
        tracker.reset()
        val after = tracker.onResult(gga(1500.0), 2_000L)
        assertEquals(1500.0, after.altitudeMslMeters)
        assertEquals(0.0, after.altitudeAglMeters)
        assertEquals(1500.0, after.maxAltitudeMslMeters)
    }

    private fun assertRejectedFirstAltitude(altitude: Double) {
        val tracker = FlightTracker()
        val after = tracker.onResult(gga(altitude), 1_000L)
        assertNotNull(after.position)
        assertEquals(1_000L, after.lastFixAtMs)
        assertNull(after.altitudeMslMeters)
        assertNull(after.altitudeAglMeters)
        assertNull(after.maxAltitudeMslMeters)
        assertNull(after.path.single().altitudeMslMeters)
        tracker.resetAltitudeBaseline()
        assertNull(tracker.state.altitudeAglMeters)
        val valid = tracker.onResult(gga(1500.0), 2_000L)
        assertEquals(1500.0, valid.altitudeMslMeters)
        assertEquals(0.0, valid.altitudeAglMeters)
        assertEquals(0.0, valid.maxAltitudeAglMeters)
    }

    private fun seededTracker(): FlightTracker = FlightTracker().also {
        it.onResult(gga(1500.0), 1_000L)
    }

    private fun gga(altitude: Double?, quality: Int = 1): NmeaParseResult = NmeaParseResult.Parsed(
        GgaSentence("GP", "120000", 40.0, -105.0, quality, 8, 0.9, altitude, null), "raw",
    )
}

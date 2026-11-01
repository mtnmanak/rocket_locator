package io.github.mtnmanak.rocketlocator26.core.flight

import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import io.github.mtnmanak.rocketlocator26.core.nmea.GgaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParseResult
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Parser-to-tracker regressions promoted from the audit reproductions. */
class MeasuredFixAcceptanceTest {
    private val parser = NmeaParser()

    @ParameterizedTest
    @ValueSource(ints = [-1, 0, 6, 7, 8, 9, 123519])
    fun rejectedGgaQualityOnlyUpdatesReceiverStatus(quality: Int) {
        val tracker = seededTracker()
        val before = tracker.state
        val rejected = gga(latitude = "4100.000", quality = "$quality", altitude = "1800.0")
            .replace(",08,0.9,", ",03,55.0,")
        val after = feed(tracker, rejected, 2_000L)
        assertFixRetained(before, after)
        assertEquals(before.altitudeMslMeters, after.altitudeMslMeters)
        assertEquals(before.maxAltitudeMslMeters, after.maxAltitudeMslMeters)
        assertEquals(before.altitudeAglMeters, after.altitudeAglMeters)
        assertEquals(before.maxAltitudeAglMeters, after.maxAltitudeAglMeters)
        assertEquals(quality, after.fixQuality)
        assertEquals(3, after.satellitesInUse)
        assertEquals(55.0, after.hdop)
        assertEquals(2_000L, after.lastSentenceAtMs)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5])
    fun measuredGgaQualitiesMovePosition(quality: Int) {
        val state = feed(FlightTracker(), gga(quality = "$quality"), 1_000L)
        assertEquals(GeoPoint(40.0, -105.0), state.position)
        assertEquals(1_000L, state.lastFixAtMs)
    }

    @ParameterizedTest
    @ValueSource(strings = ["E", "M", "N", "S", "X", "1", "AA", "AE"])
    fun unmeasuredRmcAndGllModesKeepMeasuredFix(mode: String) {
        for (body in listOf(rmc(mode), gll(mode))) {
            val tracker = seededTracker()
            val before = tracker.state
            val after = feed(tracker, body, 2_000L)
            assertFixRetained(before, after)
            assertEquals(before.speedKnots, after.speedKnots)
            assertEquals(before.courseDeg, after.courseDeg)
            assertEquals(2_000L, after.lastSentenceAtMs)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["A", "D", "F", "P", "R", ""])
    fun measuredAndEmptyRmcAndGllModesMovePosition(mode: String) {
        for (body in listOf(rmc(mode), gll(mode))) {
            val tracker = seededTracker()
            val after = feed(tracker, body, 2_000L)
            assertEquals(GeoPoint(41.0, -106.0), after.position)
            assertEquals(2_000L, after.lastFixAtMs)
            assertEquals(2, after.path.size)
        }
    }

    @Test
    fun legacyRmcAndGllWithoutModeMovePosition() {
        for (body in listOf(rmc(null), gll(null))) {
            val after = feed(seededTracker(), body, 2_000L)
            assertEquals(GeoPoint(41.0, -106.0), after.position)
            assertEquals(2_000L, after.lastFixAtMs)
        }
    }

    @Test
    fun isolatedAltitudeSpikeDoesNotPoisonCurrentOrMaximum() {
        val tracker = seededTracker()
        val spike = feed(tracker, gga(latitude = "4100.000", altitude = "99999.9"), 2_000L)
        assertEquals(GeoPoint(41.0, -105.0), spike.position)
        assertEquals(2_000L, spike.lastFixAtMs)
        assertEquals(1500.0, spike.path.last().altitudeMslMeters)
        assertEquals(1500.0, spike.altitudeMslMeters)
        assertEquals(1500.0, spike.maxAltitudeMslMeters)
        val normal = feed(tracker, gga(), 3_000L)
        assertEquals(0.0, normal.altitudeAglMeters)
        assertEquals(0.0, normal.maxAltitudeAglMeters)
    }

    @Test
    fun rejectedSpikeCannotBeResurrectedByRebaseline() {
        val tracker = seededTracker()
        feed(tracker, gga(altitude = "99999.9"), 2_000L)
        tracker.resetAltitudeBaseline()
        assertEquals(1500.0, tracker.state.altitudeMslMeters)
        assertEquals(0.0, tracker.state.altitudeAglMeters)
        assertEquals(0.0, tracker.state.maxAltitudeAglMeters)
        feed(tracker, gga(), 3_000L)
        tracker.resetAltitudeBaseline()
        assertEquals(1500.0, tracker.state.maxAltitudeMslMeters)
        assertEquals(0.0, tracker.state.maxAltitudeAglMeters)
    }

    @Test
    fun genuineFastClimbIsAccepted() {
        val after = feed(seededTracker(), gga(altitude = "1800.0"), 2_000L)
        assertEquals(1800.0, after.altitudeMslMeters)
        assertEquals(300.0, after.altitudeAglMeters)
        assertEquals(300.0, after.maxAltitudeAglMeters)
    }

    @ParameterizedTest
    @ValueSource(strings = ["4e3", "4.0e3", "+4000.000", "4000.000E0", "0x10"])
    fun nonDecimalLatitudeCannotMoveRocket(latitude: String) {
        val tracker = seededTracker()
        val measured = feed(tracker, gga(latitude = "4100.000"), 2_000L)
        assertNotEquals(GeoPoint(40.0, -105.0), measured.position)
        assertFixRetained(measured, feed(tracker, gga(latitude = latitude), 3_000L))
    }

    @Test
    fun surroundingCoordinateWhitespaceIsTolerated() {
        val after = feed(seededTracker(), gga(latitude = " 4100.000 "), 2_000L)
        assertEquals(GeoPoint(41.0, -105.0), after.position)
        assertEquals(2_000L, after.lastFixAtMs)
    }

    @ParameterizedTest
    @ValueSource(strings = ["4100.000", "400.000"])
    fun checksumlessCorruptionCannotReplaceChecksummedFix(latitude: String) {
        val tracker = seededTracker()
        val before = tracker.state
        val unchecked = checked(gga()).substringBefore('*').replace("4000.000", latitude)
        val parsed = parser.parse(unchecked)
        assertTrue(parsed is NmeaParseResult.Failed)
        assertFixRetained(before, tracker.onResult(parsed, 2_000L))
    }

    @Test
    fun corruptionWithChecksumStillPresentIsRejected() {
        val tracker = seededTracker()
        val before = tracker.state
        val parsed = parser.parse(checked(gga()).replace("4000.000", "4100.000"))
        assertTrue(parsed is NmeaParseResult.Failed)
        assertFixRetained(before, tracker.onResult(parsed, 2_000L))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1e99", "1.0E99"])
    fun exponentAltitudeCannotLatchMaximum(altitude: String) {
        val tracker = seededTracker()
        feed(tracker, gga(altitude = altitude), 2_000L)
        val after = feed(tracker, gga(), 3_000L)
        assertEquals(1500.0, after.altitudeMslMeters)
        assertEquals(1500.0, after.maxAltitudeMslMeters)
        assertEquals(0.0, after.maxAltitudeAglMeters)
    }

    @Test
    fun feetAltitudeIsIgnoredWhileHorizontalFixMoves() {
        val body = gga(latitude = "4100.000", altitude = "1000.0", unit = "F")
        val parsed = parser.parse(checked(body)) as NmeaParseResult.Parsed
        assertNull((parsed.sentence as GgaSentence).altitudeMslMeters)
        val tracker = seededTracker()
        val after = feed(tracker, body, 2_000L)
        assertEquals(GeoPoint(41.0, -105.0), after.position)
        assertEquals(2_000L, after.lastFixAtMs)
        assertEquals(1500.0, after.altitudeMslMeters)
        assertEquals(1500.0, after.maxAltitudeMslMeters)
    }

    private fun seededTracker(): FlightTracker = FlightTracker().also {
        val state = feed(it, gga(), 1_000L)
        assertEquals(GeoPoint(40.0, -105.0), state.position)
        assertEquals(1500.0, state.altitudeMslMeters)
        assertEquals(1_000L, state.lastFixAtMs)
    }

    private fun feed(tracker: FlightTracker, body: String, nowMs: Long): RocketState {
        val result = parser.parse(checked(body))
        assertTrue(result is NmeaParseResult.Parsed, "Fixture must parse: $result")
        return tracker.onResult(result, nowMs)
    }

    private fun assertFixRetained(before: RocketState, after: RocketState) {
        assertEquals(before.position, after.position, "Keep the last measured position")
        assertEquals(before.lastFixAtMs, after.lastFixAtMs, "Do not renew fix receipt time")
        assertEquals(before.path, after.path, "Do not append a rejected position")
    }

    private fun gga(
        latitude: String = "4000.000",
        quality: String = "1",
        altitude: String = "1500.0",
        unit: String = "M",
    ): String = "GPGGA,120000,$latitude,N,10500.000,W,$quality,08,0.9,$altitude,$unit,0.0,M,,"

    private fun rmc(mode: String?): String =
        "GPRMC,120001,A,4100.000,N,10600.000,W,22.4,84.4,311026,," +
            (mode?.let { ",$it" } ?: "")

    private fun gll(mode: String?): String =
        "GPGLL,4100.000,N,10600.000,W,120001,A" + (mode?.let { ",$it" } ?: "")

    private fun checked(body: String): String {
        val checksum = body.fold(0) { sum, char -> sum xor char.code }
        return "\$$body*${checksum.toString(16).uppercase().padStart(2, '0')}"
    }
}

package io.github.mtnmanak.rocketlocator26.core.flight

import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import io.github.mtnmanak.rocketlocator26.core.nmea.GgaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.GllSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.GsaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.GsvSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParseResult
import io.github.mtnmanak.rocketlocator26.core.nmea.RmcSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.VtgSentence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlightTrackerTest {

    /** Approximate meters per degree of latitude; exact value is irrelevant
     *  because every distance used sits far from the 2.0 m threshold. */
    private val metersPerDegLat = 111_320.0

    private val baseLat = 40.0
    private val baseLon = -105.0

    /** A latitude [meters] north of [baseLat]. */
    private fun latPlusMeters(meters: Double): Double = baseLat + meters / metersPerDegLat

    private fun gga(
        lat: Double? = baseLat,
        lon: Double? = baseLon,
        fixQuality: Int = 1,
        satellites: Int? = 8,
        hdop: Double? = 0.9,
        altitude: Double? = 1500.0,
    ): NmeaParseResult = NmeaParseResult.Parsed(
        GgaSentence(
            talker = "GP",
            timeUtc = "123519",
            latitude = lat,
            longitude = lon,
            fixQuality = fixQuality,
            satellitesInUse = satellites,
            hdop = hdop,
            altitudeMslMeters = altitude,
            geoidSeparationMeters = null,
        ),
        raw = "raw",
    )

    private fun rmc(
        lat: Double? = baseLat,
        lon: Double? = baseLon,
        isValid: Boolean = true,
        speedKnots: Double? = 22.4,
        courseDeg: Double? = 84.4,
    ): NmeaParseResult = NmeaParseResult.Parsed(
        RmcSentence(
            talker = "GP",
            timeUtc = "123519",
            isValid = isValid,
            latitude = lat,
            longitude = lon,
            speedKnots = speedKnots,
            courseDeg = courseDeg,
            date = "230394",
        ),
        raw = "raw",
    )

    private fun gll(
        lat: Double? = baseLat,
        lon: Double? = baseLon,
        isValid: Boolean = true,
    ): NmeaParseResult = NmeaParseResult.Parsed(
        GllSentence(talker = "GP", latitude = lat, longitude = lon, timeUtc = "123519", isValid = isValid),
        raw = "raw",
    )

    private fun vtg(course: Double? = 84.4, knots: Double? = 22.4): NmeaParseResult =
        NmeaParseResult.Parsed(
            VtgSentence(talker = "GP", courseTrueDeg = course, speedKnots = knots, speedKmh = null),
            raw = "raw",
        )

    // ------------------------------------------------------------------ GGA

    @Test
    fun `gga with fix updates position altitude quality satellites hdop and timestamps`() {
        val tracker = FlightTracker()
        val state = tracker.onResult(gga(), nowMs = 1_000L)

        assertEquals(GeoPoint(baseLat, baseLon), state.position)
        assertEquals(1500.0, state.altitudeMslMeters)
        assertEquals(1, state.fixQuality)
        assertEquals(8, state.satellitesInUse)
        assertEquals(0.9, state.hdop)
        assertEquals(1_000L, state.lastFixAtMs)
        assertEquals(1_000L, state.lastSentenceAtMs)
        assertEquals(1, state.path.size)
        assertEquals(GeoPoint(baseLat, baseLon), state.path[0].point)
        assertEquals(1500.0, state.path[0].altitudeMslMeters)
        assertEquals(1_000L, state.path[0].timestampMs)
    }

    @Test
    fun `gga with fix updates max altitudes`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(altitude = 1500.0), 1_000L)
        val state = tracker.onResult(gga(lat = latPlusMeters(10.0), altitude = 1800.0), 2_000L)

        assertEquals(1800.0, state.maxAltitudeMslMeters)
        assertEquals(1800.0, state.altitudeMslMeters)
    }

    @Test
    fun `gga with no fix never moves position or fix timestamp`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(), 1_000L)

        // A no-fix packet claiming a wildly different position must be ignored.
        val state = tracker.onResult(
            gga(lat = 0.0, lon = 0.0, fixQuality = 0, satellites = 3, hdop = 55.0, altitude = 0.0),
            2_000L,
        )

        assertEquals(GeoPoint(baseLat, baseLon), state.position, "no-fix GGA must not move position")
        assertEquals(1500.0, state.altitudeMslMeters, "no-fix GGA must not change altitude")
        assertEquals(1500.0, state.maxAltitudeMslMeters)
        assertEquals(0.9, state.hdop, "no-fix GGA must not change hdop")
        assertEquals(1_000L, state.lastFixAtMs, "no-fix GGA must not bump lastFixAtMs")
        assertEquals(1, state.path.size, "no-fix GGA must not add a path point")
        // ...but it does report link health and receiver status:
        assertEquals(0, state.fixQuality)
        assertEquals(3, state.satellitesInUse)
        assertEquals(2_000L, state.lastSentenceAtMs)
    }

    @Test
    fun `gga claiming a fix but missing coordinates does not move position`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(), 1_000L)
        val state = tracker.onResult(gga(lat = null, lon = null, fixQuality = 1), 2_000L)

        assertEquals(GeoPoint(baseLat, baseLon), state.position)
        assertEquals(1_000L, state.lastFixAtMs)
        assertEquals(1, state.path.size)
        assertEquals(2_000L, state.lastSentenceAtMs)
    }

    @Test
    fun `gga with null altitude keeps last known altitude`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(altitude = 1500.0), 1_000L)
        val state = tracker.onResult(gga(lat = latPlusMeters(10.0), altitude = null), 2_000L)

        assertEquals(1500.0, state.altitudeMslMeters)
        assertEquals(1500.0, state.maxAltitudeMslMeters)
        assertEquals(0.0, state.altitudeAglMeters)
    }

    // ------------------------------------------------------------------ RMC

    @Test
    fun `valid rmc updates position speed course and fix timestamp`() {
        val tracker = FlightTracker()
        val state = tracker.onResult(rmc(), 1_000L)

        assertEquals(GeoPoint(baseLat, baseLon), state.position)
        assertEquals(22.4, state.speedKnots)
        assertEquals(84.4, state.courseDeg)
        assertEquals(1_000L, state.lastFixAtMs)
        assertEquals(1_000L, state.lastSentenceAtMs)
        assertEquals(1, state.path.size)
    }

    @Test
    fun `invalid rmc updates telemetry timestamp only`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(), 1_000L)
        val state = tracker.onResult(
            rmc(lat = 0.0, lon = 0.0, isValid = false, speedKnots = 999.0, courseDeg = 1.0),
            2_000L,
        )

        assertEquals(GeoPoint(baseLat, baseLon), state.position, "invalid RMC must not move position")
        assertNull(state.speedKnots, "invalid RMC must not set speed")
        assertNull(state.courseDeg, "invalid RMC must not set course")
        assertEquals(1_000L, state.lastFixAtMs)
        assertEquals(1, state.path.size)
        assertEquals(2_000L, state.lastSentenceAtMs)
    }

    @Test
    fun `valid rmc without coordinates updates telemetry timestamp only`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(), 1_000L)
        val state = tracker.onResult(rmc(lat = null, lon = null, isValid = true), 2_000L)

        assertEquals(GeoPoint(baseLat, baseLon), state.position)
        assertEquals(1_000L, state.lastFixAtMs)
        assertEquals(2_000L, state.lastSentenceAtMs)
    }

    // ------------------------------------------------------------ GLL / VTG

    @Test
    fun `valid gll updates position and fix timestamp`() {
        val tracker = FlightTracker()
        val state = tracker.onResult(gll(), 1_000L)

        assertEquals(GeoPoint(baseLat, baseLon), state.position)
        assertEquals(1_000L, state.lastFixAtMs)
        assertEquals(1, state.path.size)
    }

    @Test
    fun `invalid gll does not move position`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(), 1_000L)
        val state = tracker.onResult(gll(lat = 0.0, lon = 0.0, isValid = false), 2_000L)

        assertEquals(GeoPoint(baseLat, baseLon), state.position)
        assertEquals(1_000L, state.lastFixAtMs)
        assertEquals(2_000L, state.lastSentenceAtMs)
    }

    @Test
    fun `vtg updates speed and course but not position or fix timestamp`() {
        val tracker = FlightTracker()
        val state = tracker.onResult(vtg(course = 180.0, knots = 5.5), 1_000L)

        assertEquals(5.5, state.speedKnots)
        assertEquals(180.0, state.courseDeg)
        assertNull(state.position)
        assertNull(state.lastFixAtMs)
        assertEquals(1_000L, state.lastSentenceAtMs)
    }

    // ------------------------------------------------------------ GSA / GSV

    @Test
    fun `gsa and gsv update sentence timestamp only`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(), 1_000L)
        val before = tracker.state

        val afterGsa = tracker.onResult(
            NmeaParseResult.Parsed(
                GsaSentence(talker = "GP", fixType = 3, pdop = 1.8, hdop = 42.0, vdop = 1.5),
                raw = "raw",
            ),
            2_000L,
        )
        assertEquals(2_000L, afterGsa.lastSentenceAtMs)
        assertEquals(before.copy(lastSentenceAtMs = 2_000L), afterGsa, "GSA must change nothing but lastSentenceAtMs")

        val afterGsv = tracker.onResult(
            NmeaParseResult.Parsed(
                GsvSentence(talker = "GP", totalMessages = 3, messageNumber = 1, satellitesInView = 11),
                raw = "raw",
            ),
            3_000L,
        )
        assertEquals(before.copy(lastSentenceAtMs = 3_000L), afterGsv, "GSV must change nothing but lastSentenceAtMs")
    }

    // -------------------------------------------------- Failed / Unsupported

    @Test
    fun `failed and unsupported results update nothing at all`() {
        val tracker = FlightTracker()

        tracker.onResult(NmeaParseResult.Failed("bad checksum", "raw"), 1_000L)
        assertEquals(RocketState(), tracker.state, "Failed must not even bump timestamps")

        tracker.onResult(NmeaParseResult.Unsupported("ZDA", "raw"), 2_000L)
        assertEquals(RocketState(), tracker.state, "Unsupported must not even bump timestamps")

        // Also after real data has arrived:
        tracker.onResult(gga(), 3_000L)
        val before = tracker.state
        tracker.onResult(NmeaParseResult.Failed("garbage", "raw"), 4_000L)
        assertEquals(before, tracker.state)
    }

    // ------------------------------------------------------ Altitude / AGL

    @Test
    fun `first gga altitude becomes agl baseline`() {
        val tracker = FlightTracker()
        val state = tracker.onResult(gga(altitude = 1500.0), 1_000L)

        assertEquals(0.0, state.altitudeAglMeters, "first altitude is the pad, AGL zero")
        assertEquals(0.0, state.maxAltitudeAglMeters)
        assertEquals(1500.0, state.maxAltitudeMslMeters)
    }

    @Test
    fun `max altitude holds at apogee through descent`() {
        val tracker = FlightTracker()
        var t = 1_000L
        // Ascent, apogee, descent, landing — each a few meters downrange so
        // path decimation is not a factor here.
        val flight = listOf(1500.0, 1900.0, 3000.0, 2200.0, 1520.0)
        var state = tracker.state
        flight.forEachIndexed { i, alt ->
            state = tracker.onResult(gga(lat = latPlusMeters(i * 10.0), altitude = alt), t)
            t += 1_000L
        }

        assertEquals(3000.0, state.maxAltitudeMslMeters, "max MSL must hold at apogee")
        assertEquals(1500.0, state.maxAltitudeAglMeters!!, 1e-9, "max AGL = apogee - pad baseline")
        assertEquals(1520.0, state.altitudeMslMeters)
        assertEquals(20.0, state.altitudeAglMeters!!, 1e-9, "landed 20 m above the pad")
    }

    @Test
    fun `resetAltitudeBaseline rebaselines current and max agl`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(altitude = 1500.0), 1_000L)
        tracker.onResult(gga(lat = latPlusMeters(10.0), altitude = 3000.0), 2_000L)
        tracker.onResult(gga(lat = latPlusMeters(20.0), altitude = 1520.0), 3_000L)

        tracker.resetAltitudeBaseline()
        val state = tracker.state

        assertEquals(0.0, state.altitudeAglMeters!!, 1e-9, "current altitude becomes AGL zero")
        assertEquals(1480.0, state.maxAltitudeAglMeters!!, 1e-9, "max AGL recomputed against new baseline")
        assertEquals(3000.0, state.maxAltitudeMslMeters, "max MSL untouched by rebaseline")
        assertEquals(1520.0, state.altitudeMslMeters)
    }

    @Test
    fun `resetAltitudeBaseline with no altitude known is a safe no-op`() {
        val tracker = FlightTracker()
        tracker.resetAltitudeBaseline()
        assertNull(tracker.state.altitudeAglMeters)

        // The next altitude still establishes a baseline normally.
        val state = tracker.onResult(gga(altitude = 1500.0), 1_000L)
        assertEquals(0.0, state.altitudeAglMeters)
    }

    @Test
    fun `agl is null before any altitude arrives`() {
        val tracker = FlightTracker()
        val state = tracker.onResult(gga(altitude = null), 1_000L)

        assertNotNull(state.position)
        assertNull(state.altitudeMslMeters)
        assertNull(state.altitudeAglMeters)
        assertNull(state.maxAltitudeAglMeters)
    }

    // ---------------------------------------------------------------- Path

    @Test
    fun `path point within two meters is not appended but position still updates`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(), 1_000L)

        val oneMeterNorth = latPlusMeters(1.0)
        val state = tracker.onResult(gga(lat = oneMeterNorth), 2_000L)

        assertEquals(1, state.path.size, "1 m of jitter must not grow the path")
        assertEquals(GeoPoint(baseLat, baseLon), state.path[0].point)
        assertEquals(GeoPoint(oneMeterNorth, baseLon), state.position, "position itself must still update")
        assertEquals(2_000L, state.lastFixAtMs)
    }

    @Test
    fun `path point beyond two meters is appended`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(), 1_000L)

        val threeMetersNorth = latPlusMeters(3.0)
        val state = tracker.onResult(gga(lat = threeMetersNorth), 2_000L)

        assertEquals(2, state.path.size)
        assertEquals(GeoPoint(threeMetersNorth, baseLon), state.path[1].point)
        assertEquals(2_000L, state.path[1].timestampMs)
    }

    @Test
    fun `path cap drops oldest points but always preserves the launch point`() {
        val tracker = FlightTracker(maxPathPoints = 3)
        // Five points, 5 m apart, all beyond the decimation threshold.
        val lats = (0 until 5).map { latPlusMeters(it * 5.0) }
        lats.forEachIndexed { i, lat ->
            tracker.onResult(gga(lat = lat), 1_000L + i)
        }
        val path = tracker.state.path

        assertEquals(3, path.size)
        assertEquals(GeoPoint(lats[0], baseLon), path[0].point, "launch point must never rotate out")
        assertEquals(GeoPoint(lats[3], baseLon), path[1].point)
        assertEquals(GeoPoint(lats[4], baseLon), path[2].point, "newest point must be last")
    }

    @Test
    fun `path grows across mixed gga rmc and gll fixes`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(altitude = 1500.0), 1_000L)
        tracker.onResult(rmc(lat = latPlusMeters(5.0)), 2_000L)
        val state = tracker.onResult(gll(lat = latPlusMeters(10.0)), 3_000L)

        assertEquals(3, state.path.size)
        // RMC/GLL carry no altitude; the path point records the last known MSL.
        assertEquals(1500.0, state.path[1].altitudeMslMeters)
        assertEquals(1500.0, state.path[2].altitudeMslMeters)
    }

    // ------------------------------------------------------------- Restore

    @Test
    fun `restoreLastPosition sets position but leaves lastFixAtMs null and path empty`() {
        val tracker = FlightTracker()
        tracker.restoreLastPosition(GeoPoint(baseLat, baseLon), 1500.0)
        val state = tracker.state

        assertEquals(GeoPoint(baseLat, baseLon), state.position)
        assertEquals(1500.0, state.altitudeMslMeters)
        assertNull(state.lastFixAtMs, "restored data is stale; staleness must stay visible")
        assertNull(state.lastSentenceAtMs)
        assertTrue(state.path.isEmpty(), "restore must not add a path point")
        assertNull(state.maxAltitudeMslMeters, "restore must not fake a max altitude")
    }

    @Test
    fun `restoreLastPosition with null altitude keeps altitude unknown`() {
        val tracker = FlightTracker()
        tracker.restoreLastPosition(GeoPoint(baseLat, baseLon), null)

        assertEquals(GeoPoint(baseLat, baseLon), tracker.state.position)
        assertNull(tracker.state.altitudeMslMeters)
        assertNull(tracker.state.altitudeAglMeters)
    }

    @Test
    fun `live fix after restore updates lastFixAtMs normally`() {
        val tracker = FlightTracker()
        tracker.restoreLastPosition(GeoPoint(baseLat, baseLon), 1500.0)
        val state = tracker.onResult(gga(lat = latPlusMeters(5.0)), 9_000L)

        assertEquals(9_000L, state.lastFixAtMs)
        assertEquals(1, state.path.size, "first live fix starts the path")
    }

    // --------------------------------------------------------------- Reset

    @Test
    fun `reset returns to pristine state and clears the altitude baseline`() {
        val tracker = FlightTracker()
        tracker.onResult(gga(altitude = 1500.0), 1_000L)
        tracker.onResult(gga(lat = latPlusMeters(10.0), altitude = 3000.0), 2_000L)

        tracker.reset()
        assertEquals(RocketState(), tracker.state)

        // Baseline was cleared: the next flight's first altitude is AGL zero,
        // even though it differs from the previous baseline.
        val state = tracker.onResult(gga(altitude = 250.0), 3_000L)
        assertEquals(0.0, state.altitudeAglMeters, "new baseline after reset")
        assertEquals(250.0, state.maxAltitudeMslMeters, "old max must not survive reset")
        assertEquals(1, state.path.size, "old path must not survive reset")
    }

    @Test
    fun `onResult returns the same snapshot exposed by state`() {
        val tracker = FlightTracker()
        val returned = tracker.onResult(gga(), 1_000L)
        assertEquals(tracker.state, returned)
    }

    @Test
    fun `restored stale altitude never becomes baseline or max via an altitude-less live fix`() {
        // Regression: previous outing ended at 1200 m MSL; new site is 150 m MSL.
        val tracker = FlightTracker()
        tracker.restoreLastPosition(GeoPoint(baseLat, baseLon), altitudeMslMeters = 1200.0)

        // First live fix during early acquisition: valid position, empty altitude field.
        tracker.onResult(gga(altitude = null), 1_000L)
        assertNull(tracker.state.maxAltitudeMslMeters, "stale altitude must not fake a max")
        assertNull(tracker.state.altitudeAglMeters, "no baseline may exist yet")

        // Real altitude arrives: THIS establishes the baseline, so AGL reads 0.
        tracker.onResult(gga(altitude = 150.0), 2_000L)
        assertEquals(0.0, tracker.state.altitudeAglMeters!!, 1e-9)
        assertEquals(150.0, tracker.state.maxAltitudeMslMeters!!, 1e-9)

        // Apogee is tracked from the true baseline, not the stale 1200 m.
        tracker.onResult(gga(altitude = 1150.0), 3_000L)
        assertEquals(1150.0, tracker.state.maxAltitudeMslMeters!!, 1e-9)
        assertEquals(1000.0, tracker.state.maxAltitudeAglMeters!!, 1e-9)
    }
}

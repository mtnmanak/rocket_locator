package io.github.mtnmanak.rocketlocator26.core.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Known-answer tests for [Geodesy].
 *
 * All hard-coded expected values were computed with an independent haversine /
 * forward-azimuth implementation (IEEE-754 double precision, same IUGG radius
 * 6371008.8 m), NOT by running the production code. Tolerances are far tighter
 * than GPS fix error but loose enough to absorb legitimate floating-point
 * ordering differences.
 */
class GeodesyTest {

    /** Distance tolerance for km-scale known answers, meters. */
    private val distTolShort = 1e-6

    /** Distance tolerance for continental-scale known answers, meters. */
    private val distTolLong = 1e-2

    /** Bearing tolerance, degrees. */
    private val brgTol = 1e-9

    // ------------------------------------------------------------------
    // distanceMeters
    // ------------------------------------------------------------------

    @Test
    fun `identical points give exactly zero distance`() {
        val p = GeoPoint(43.7956, -120.6543)
        assertEquals(0.0, Geodesy.distanceMeters(p, p))
    }

    @Test
    fun `one degree of longitude along the equator`() {
        // Arc of 1 degree on a great circle: R * pi / 180.
        val d = Geodesy.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(111195.08023353291, d, distTolShort)
    }

    @Test
    fun `one degree of latitude along a meridian`() {
        val d = Geodesy.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))
        assertEquals(111195.08023353291, d, distTolShort)
    }

    @Test
    fun `kilometer-scale diagonal known answer`() {
        // (50.0, 6.0) -> (50.005, 6.008): independently computed haversine result.
        val from = GeoPoint(50.0, 6.0)
        val to = GeoPoint(50.005, 6.008)
        assertEquals(797.51378297329643, Geodesy.distanceMeters(from, to), distTolShort)
    }

    @Test
    fun `kilometer-scale diagonal cross-checked against spherical law of cosines`() {
        // Independent formula computed here in the test, not haversine.
        val from = GeoPoint(50.0, 6.0)
        val to = GeoPoint(50.005, 6.008)
        val lat1 = from.latitude * PI / 180.0
        val lat2 = to.latitude * PI / 180.0
        val dLon = (to.longitude - from.longitude) * PI / 180.0
        val central = acos(
            (sin(lat1) * sin(lat2) + cos(lat1) * cos(lat2) * cos(dLon)).coerceIn(-1.0, 1.0),
        )
        val expected = Geodesy.EARTH_RADIUS_METERS * central
        assertEquals(expected, Geodesy.distanceMeters(from, to), 1e-3)
    }

    @Test
    fun `antimeridian crossing measures the short way around`() {
        // 0.5 degrees each side of the 180 meridian: 1 degree apart, NOT 359.
        val d = Geodesy.distanceMeters(GeoPoint(0.0, 179.5), GeoPoint(0.0, -179.5))
        assertEquals(111195.08023353887, d, distTolShort)
        assertTrue(d < 200_000.0, "naive longitude delta of 359 degrees would give ~39,000 km")
    }

    @Test
    fun `pole to equator is a quarter circumference`() {
        val d = Geodesy.distanceMeters(GeoPoint(90.0, 0.0), GeoPoint(0.0, 0.0))
        assertEquals(Geodesy.EARTH_RADIUS_METERS * PI / 2.0, d, 1e-3)
        assertEquals(10007557.221017962, d, distTolLong)
    }

    @Test
    fun `near-pole point to the north pole`() {
        val d = Geodesy.distanceMeters(GeoPoint(89.0, 0.0), GeoPoint(90.0, 0.0))
        assertEquals(111195.08023353291, d, distTolShort)
    }

    @Test
    fun `Movable Type canonical fixture`() {
        // 50 03 59N, 5 42 53W -> 58 38 38N, 3 04 12W (the classic worked example),
        // recomputed independently with R = 6371008.8 m.
        val from = GeoPoint(50.066389, -5.714722)
        val to = GeoPoint(58.643889, -3.07)
        assertEquals(968854.8823543567, Geodesy.distanceMeters(from, to), distTolLong)
    }

    @Test
    fun `transatlantic JFK to LHR fixture`() {
        val jfk = GeoPoint(40.6413, -73.7781)
        val lhr = GeoPoint(51.47, -0.4543)
        assertEquals(5540018.970166089, Geodesy.distanceMeters(jfk, lhr), distTolLong)
    }

    @Test
    fun `distance is symmetric`() {
        val a = GeoPoint(37.7749, -122.4194)
        val b = GeoPoint(34.0522, -118.2437)
        assertEquals(Geodesy.distanceMeters(a, b), Geodesy.distanceMeters(b, a), 1e-9)
    }

    @Test
    fun `distance is never negative across a coordinate grid`() {
        val lats = listOf(-90.0, -45.0, 0.0, 45.0, 89.9, 90.0)
        val lons = listOf(-180.0, -90.0, 0.0, 90.0, 179.9, 180.0)
        for (lat1 in lats) for (lon1 in lons) for (lat2 in lats) for (lon2 in lons) {
            val d = Geodesy.distanceMeters(GeoPoint(lat1, lon1), GeoPoint(lat2, lon2))
            assertTrue(d >= 0.0 && d.isFinite(), "d=$d for ($lat1,$lon1)->($lat2,$lon2)")
        }
    }

    // ------------------------------------------------------------------
    // initialBearingDeg
    // ------------------------------------------------------------------

    @Test
    fun `due east along the equator is 90`() {
        assertEquals(90.0, Geodesy.initialBearingDeg(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), brgTol)
    }

    @Test
    fun `due north along a meridian is 0`() {
        assertEquals(0.0, Geodesy.initialBearingDeg(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), brgTol)
    }

    @Test
    fun `due south along a meridian is 180`() {
        assertEquals(180.0, Geodesy.initialBearingDeg(GeoPoint(10.0, 10.0), GeoPoint(9.0, 10.0)), brgTol)
    }

    @Test
    fun `due west along the equator is 270 not minus 90`() {
        assertEquals(270.0, Geodesy.initialBearingDeg(GeoPoint(0.0, 0.0), GeoPoint(0.0, -1.0)), brgTol)
    }

    @Test
    fun `kilometer-scale diagonal bearing known answer`() {
        val b = Geodesy.initialBearingDeg(GeoPoint(50.0, 6.0), GeoPoint(50.005, 6.008))
        assertEquals(45.799278471607067, b, brgTol)
    }

    @Test
    fun `antimeridian crossing bearings point the short way`() {
        assertEquals(90.0, Geodesy.initialBearingDeg(GeoPoint(0.0, 179.5), GeoPoint(0.0, -179.5)), brgTol)
        assertEquals(270.0, Geodesy.initialBearingDeg(GeoPoint(0.0, -179.5), GeoPoint(0.0, 179.5)), brgTol)
    }

    @Test
    fun `Movable Type canonical bearing fixture`() {
        val b = Geodesy.initialBearingDeg(GeoPoint(50.066389, -5.714722), GeoPoint(58.643889, -3.07))
        assertEquals(9.1198173274103738, b, 1e-6)
    }

    @Test
    fun `transatlantic bearing fixture`() {
        val b = Geodesy.initialBearingDeg(GeoPoint(40.6413, -73.7781), GeoPoint(51.47, -0.4543))
        assertEquals(51.352520866425493, b, 1e-6)
    }

    @Test
    fun `bearing toward the north pole is 0`() {
        assertEquals(0.0, Geodesy.initialBearingDeg(GeoPoint(89.0, 0.0), GeoPoint(90.0, 0.0)), brgTol)
    }

    @Test
    fun `identical points give the documented 0 bearing`() {
        val p = GeoPoint(12.34, 56.78)
        assertEquals(0.0, Geodesy.initialBearingDeg(p, p))
    }

    @Test
    fun `bearing is always in 0 until 360 across a coordinate grid`() {
        val coords = listOf(-89.0, -45.0, 0.0, 45.0, 89.0)
        val lons = listOf(-179.0, -90.0, 0.0, 90.0, 179.0)
        for (lat1 in coords) for (lon1 in lons) for (lat2 in coords) for (lon2 in lons) {
            val b = Geodesy.initialBearingDeg(GeoPoint(lat1, lon1), GeoPoint(lat2, lon2))
            assertTrue(b >= 0.0 && b < 360.0, "b=$b for ($lat1,$lon1)->($lat2,$lon2)")
        }
    }

    // ------------------------------------------------------------------
    // relativeBearingDeg
    // ------------------------------------------------------------------

    @Test
    fun `heading 350 to bearing 10 is plus 20 - turn right across north`() {
        assertEquals(20.0, Geodesy.relativeBearingDeg(350.0, 10.0), 1e-12)
    }

    @Test
    fun `heading 10 to bearing 350 is minus 20 - turn left across north`() {
        assertEquals(-20.0, Geodesy.relativeBearingDeg(10.0, 350.0), 1e-12)
    }

    @Test
    fun `target exactly behind is plus 180 never minus 180`() {
        assertEquals(180.0, Geodesy.relativeBearingDeg(0.0, 180.0), 1e-12)
        assertEquals(180.0, Geodesy.relativeBearingDeg(90.0, 270.0), 1e-12)
        assertEquals(180.0, Geodesy.relativeBearingDeg(270.0, 90.0), 1e-12)
        assertEquals(180.0, Geodesy.relativeBearingDeg(359.0, 179.0), 1e-12)
    }

    @Test
    fun `just past behind flips sign correctly`() {
        assertEquals(179.5, Geodesy.relativeBearingDeg(0.0, 179.5), 1e-12)
        assertEquals(-179.5, Geodesy.relativeBearingDeg(0.0, 180.5), 1e-12)
    }

    @Test
    fun `dead ahead is zero`() {
        assertEquals(0.0, Geodesy.relativeBearingDeg(0.0, 0.0), 1e-12)
        assertEquals(0.0, Geodesy.relativeBearingDeg(123.4, 123.4), 1e-12)
        assertEquals(0.0, Geodesy.relativeBearingDeg(360.0, 0.0), 1e-12)
    }

    @Test
    fun `target slightly left of ahead is negative`() {
        assertEquals(-10.0, Geodesy.relativeBearingDeg(0.0, 350.0), 1e-12)
    }

    @Test
    fun `unnormalized inputs are handled`() {
        assertEquals(20.0, Geodesy.relativeBearingDeg(-10.0, 10.0), 1e-12)
        assertEquals(20.0, Geodesy.relativeBearingDeg(370.0, 30.0), 1e-12)
        assertEquals(5.0, Geodesy.relativeBearingDeg(0.0, 725.0), 1e-12)
        assertEquals(-20.0, Geodesy.relativeBearingDeg(730.0, -10.0), 1e-12)
    }

    @Test
    fun `result is always in minus 180 exclusive to 180 inclusive`() {
        var h = 0.0
        while (h < 360.0) {
            var b = 0.0
            while (b < 360.0) {
                val r = Geodesy.relativeBearingDeg(h, b)
                assertTrue(r > -180.0 && r <= 180.0, "r=$r for heading=$h bearing=$b")
                b += 11.25
            }
            h += 7.5
        }
    }

    @Test
    fun `sign convention matches turn direction on a concrete recovery scenario`() {
        // Walking north (heading 0), rocket is northeast: positive (turn right).
        val user = GeoPoint(40.0, -105.0)
        val rocket = GeoPoint(40.001, -104.999)
        val bearing = Geodesy.initialBearingDeg(user, rocket)
        val rel = Geodesy.relativeBearingDeg(0.0, bearing)
        assertTrue(rel > 0.0 && rel < 90.0, "expected right turn, got $rel")
        // Same rocket while walking east (heading 90): now it is to the left.
        val relFacingEast = Geodesy.relativeBearingDeg(90.0, bearing)
        assertTrue(relFacingEast < 0.0 && relFacingEast > -90.0, "expected left turn, got $relFacingEast")
    }

    // ------------------------------------------------------------------
    // normalizeDeg360
    // ------------------------------------------------------------------

    @Test
    fun `values already in range pass through`() {
        assertEquals(0.0, Geodesy.normalizeDeg360(0.0))
        assertEquals(179.25, Geodesy.normalizeDeg360(179.25))
        assertEquals(359.999, Geodesy.normalizeDeg360(359.999))
    }

    @Test
    fun `exact full turns collapse to zero`() {
        assertEquals(0.0, Geodesy.normalizeDeg360(360.0))
        assertEquals(0.0, Geodesy.normalizeDeg360(720.0))
        assertEquals(0.0, Geodesy.normalizeDeg360(-360.0))
        assertEquals(0.0, Geodesy.normalizeDeg360(-720.0))
    }

    @Test
    fun `negatives wrap into range`() {
        assertEquals(270.0, Geodesy.normalizeDeg360(-90.0))
        assertEquals(270.0, Geodesy.normalizeDeg360(-450.0))
        assertEquals(359.0, Geodesy.normalizeDeg360(-1.0))
    }

    @Test
    fun `values above 360 wrap into range`() {
        assertEquals(90.0, Geodesy.normalizeDeg360(450.0))
        assertEquals(0.5, Geodesy.normalizeDeg360(360.5))
        assertEquals(280.0, Geodesy.normalizeDeg360(1_000_000.0))
    }

    @Test
    fun `negative zero input yields positive zero`() {
        // assertEquals distinguishes -0.0 from 0.0 for boxed doubles; this must be +0.0.
        assertEquals(0.0, Geodesy.normalizeDeg360(-0.0))
    }

    @Test
    fun `tiny negative inputs never return 360`() {
        // -1e-14 + 360.0 rounds to exactly 360.0 in double math; the result must
        // still satisfy the [0, 360) contract.
        for (deg in listOf(-1e-14, -1e-13, -4.9e-324)) {
            val r = Geodesy.normalizeDeg360(deg)
            assertTrue(r >= 0.0 && r < 360.0, "normalizeDeg360($deg) = $r out of [0, 360)")
        }
    }

    @Test
    fun `result is always in range for a sweep of inputs`() {
        var deg = -1080.0
        while (deg <= 1080.0) {
            val r = Geodesy.normalizeDeg360(deg)
            assertTrue(r >= 0.0 && r < 360.0, "normalizeDeg360($deg) = $r")
            // Normalization must preserve the angle modulo 360.
            val diff = abs(r - deg) % 360.0
            assertTrue(diff < 1e-9 || abs(diff - 360.0) < 1e-9, "angle changed: $deg -> $r")
            deg += 13.7
        }
    }
}

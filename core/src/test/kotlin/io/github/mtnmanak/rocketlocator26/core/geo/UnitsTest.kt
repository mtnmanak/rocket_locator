package io.github.mtnmanak.rocketlocator26.core.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Tests for [Units]. Conversions must use the exact legal constants
 * (1 ft = 0.3048 m, 1 mile = 1609.344 m, 1 NM = 1852 m), so tests assert
 * bit-exact equality wherever the arithmetic is exact and use tiny ulp-scale
 * tolerances only where double rounding legitimately intervenes.
 */
class UnitsTest {

    // ------------------------------------------------------------------
    // feet <-> meters
    // ------------------------------------------------------------------

    @Test
    fun `one foot of meters is exactly one foot`() {
        assertEquals(1.0, Units.metersToFeet(0.3048))
    }

    @Test
    fun `one meter in feet matches the exact reciprocal`() {
        assertEquals(1.0 / 0.3048, Units.metersToFeet(1.0))
        assertEquals(3.280839895013123, Units.metersToFeet(1.0), 1e-12)
    }

    @Test
    fun `one foot to meters is exactly the defining constant`() {
        assertEquals(0.3048, Units.feetToMeters(1.0))
    }

    @Test
    fun `thousand feet is 304 point 8 meters`() {
        assertEquals(304.8, Units.feetToMeters(1000.0), 1e-9)
    }

    @Test
    fun `typical rocket apogee altitude converts correctly`() {
        // A 10,000 ft flight is 3048 m exactly.
        assertEquals(3048.0, Units.feetToMeters(10_000.0), 1e-9)
        assertEquals(10_000.0, Units.metersToFeet(3048.0), 1e-9)
    }

    @Test
    fun `feet-meters round trips return the original value`() {
        for (x in listOf(0.0, 1.0, 0.3048, 123.456, 545.4, 100_000.0, -5.5)) {
            val tol = abs(x) * 1e-12 + 1e-15
            assertEquals(x, Units.feetToMeters(Units.metersToFeet(x)), tol)
            assertEquals(x, Units.metersToFeet(Units.feetToMeters(x)), tol)
        }
    }

    @Test
    fun `zero and sign are preserved`() {
        assertEquals(0.0, Units.metersToFeet(0.0))
        assertEquals(0.0, Units.feetToMeters(0.0))
        assertTrue(Units.metersToFeet(-1.0) < 0.0)
        assertTrue(Units.feetToMeters(-1.0) < 0.0)
    }

    // ------------------------------------------------------------------
    // meters -> miles / kilometers
    // ------------------------------------------------------------------

    @Test
    fun `one mile of meters is exactly one mile`() {
        assertEquals(1.0, Units.metersToMiles(1609.344))
    }

    @Test
    fun `two miles of meters is exactly two miles`() {
        // 3218.688 is exactly 2 * 1609.344 in double arithmetic.
        assertEquals(2.0, Units.metersToMiles(3218.688))
    }

    @Test
    fun `half a mile of meters`() {
        assertEquals(0.5, Units.metersToMiles(804.672))
    }

    @Test
    fun `one kilometer of meters is exactly one`() {
        assertEquals(1.0, Units.metersToKilometers(1000.0))
    }

    @Test
    fun `kilometers conversion of a fractional value`() {
        assertEquals(1.2345, Units.metersToKilometers(1234.5))
        assertEquals(0.001, Units.metersToKilometers(1.0), 1e-15)
    }

    @Test
    fun `miles and kilometers of zero are zero`() {
        assertEquals(0.0, Units.metersToMiles(0.0))
        assertEquals(0.0, Units.metersToKilometers(0.0))
    }

    @Test
    fun `miles and kilometers agree with each other`() {
        // 1 mile = 1.609344 km exactly.
        val m = 5000.0
        assertEquals(Units.metersToKilometers(m), Units.metersToMiles(m) * 1.609344, 1e-12)
    }

    // ------------------------------------------------------------------
    // knots -> m/s, km/h, mph
    // ------------------------------------------------------------------

    @Test
    fun `one knot in meters per second is exactly 1852 over 3600`() {
        assertEquals(1852.0 / 3600.0, Units.knotsToMetersPerSecond(1.0))
        assertEquals(0.5144444444444445, Units.knotsToMetersPerSecond(1.0), 1e-15)
    }

    @Test
    fun `3600 knots is exactly 1852 meters per second`() {
        // 3600 * 1852 = 6,667,200 exactly; divided by 3600 is exactly 1852.
        assertEquals(1852.0, Units.knotsToMetersPerSecond(3600.0))
    }

    @Test
    fun `one knot is exactly 1 point 852 kmh`() {
        assertEquals(1.852, Units.knotsToKmh(1.0))
    }

    @Test
    fun `kmh agrees with meters per second times 3 point 6`() {
        for (kn in listOf(0.0, 1.0, 22.4, 100.0, 543.21)) {
            assertEquals(Units.knotsToMetersPerSecond(kn) * 3.6, Units.knotsToKmh(kn), 1e-12 * (1.0 + kn))
        }
    }

    @Test
    fun `one knot in mph matches the exact constant ratio`() {
        assertEquals(1852.0 / 1609.344, Units.knotsToMph(1.0))
        assertEquals(1.1507794480235425, Units.knotsToMph(1.0), 1e-12)
    }

    @Test
    fun `mph agrees with meters per second converted through miles`() {
        for (kn in listOf(0.0, 1.0, 22.4, 100.0)) {
            val viaMeters = Units.metersToMiles(Units.knotsToMetersPerSecond(kn) * 3600.0)
            assertEquals(viaMeters, Units.knotsToMph(kn), 1e-9 * (1.0 + kn))
        }
    }

    @Test
    fun `RMC ground speed fixture - 22 point 4 knots`() {
        // Speed field from the canonical GPRMC fixture used across the app's tests.
        assertEquals(22.4 * 1852.0 / 3600.0, Units.knotsToMetersPerSecond(22.4), 1e-12)
        assertEquals(41.4848, Units.knotsToKmh(22.4), 1e-9)
        assertEquals(25.77745963572735, Units.knotsToMph(22.4), 1e-9)
    }

    @Test
    fun `knot conversions preserve zero and sign`() {
        assertEquals(0.0, Units.knotsToMetersPerSecond(0.0))
        assertEquals(0.0, Units.knotsToKmh(0.0))
        assertEquals(0.0, Units.knotsToMph(0.0))
        assertTrue(Units.knotsToMetersPerSecond(-2.0) < 0.0)
        assertTrue(Units.knotsToKmh(-2.0) < 0.0)
        assertTrue(Units.knotsToMph(-2.0) < 0.0)
    }

    @Test
    fun `conversions scale linearly`() {
        assertEquals(10.0 * Units.knotsToKmh(1.0), Units.knotsToKmh(10.0), 1e-12)
        assertEquals(10.0 * Units.metersToFeet(1.0), Units.metersToFeet(10.0), 1e-12)
        assertEquals(10.0 * Units.metersToMiles(1.0), Units.metersToMiles(10.0), 1e-15)
    }
}

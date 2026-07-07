package io.github.mtnmanak.rocketlocator26.core.geo

/**
 * Unit conversions for display. All factors are the exact legal definitions:
 * 1 ft = 0.3048 m, 1 mile = 1609.344 m, 1 nautical mile = 1852 m,
 * hence 1 knot = 1852/3600 m/s = 1.852 km/h exactly.
 */
object Units {

    private const val METERS_PER_FOOT: Double = 0.3048
    private const val METERS_PER_MILE: Double = 1609.344
    private const val METERS_PER_NAUTICAL_MILE: Double = 1852.0
    private const val SECONDS_PER_HOUR: Double = 3600.0
    private const val KMH_PER_KNOT: Double = 1.852

    /** Converts meters to international feet. */
    fun metersToFeet(m: Double): Double = m / METERS_PER_FOOT

    /** Converts international feet to meters. */
    fun feetToMeters(ft: Double): Double = ft * METERS_PER_FOOT

    /** Converts meters to statute miles. */
    fun metersToMiles(m: Double): Double = m / METERS_PER_MILE

    /** Converts meters to kilometers. */
    fun metersToKilometers(m: Double): Double = m / 1000.0

    /** Converts knots to meters per second (1 kn = 1852 m / 3600 s exactly). */
    fun knotsToMetersPerSecond(kn: Double): Double = kn * METERS_PER_NAUTICAL_MILE / SECONDS_PER_HOUR

    /** Converts knots to kilometers per hour (1 kn = 1.852 km/h exactly). */
    fun knotsToKmh(kn: Double): Double = kn * KMH_PER_KNOT

    /** Converts knots to statute miles per hour (1 kn = 1852/1609.344 mph). */
    fun knotsToMph(kn: Double): Double = kn * METERS_PER_NAUTICAL_MILE / METERS_PER_MILE
}

package io.github.mtnmanak.rocketlocator26.core.flight

import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint

/**
 * One recorded point along the rocket's ground track.
 *
 * @property point horizontal position of the sample.
 * @property altitudeMslMeters altitude above mean sea level at the time the
 *   sample was recorded, or null if no altitude was known yet.
 * @property timestampMs receiver-local wall-clock time (epoch millis) at which
 *   the sample was recorded.
 */
data class TrackPoint(
    val point: GeoPoint,
    val altitudeMslMeters: Double?,
    val timestampMs: Long,
)

/**
 * Immutable snapshot of everything the app knows about the rocket.
 *
 * All fields default to "unknown" so a freshly constructed state represents
 * a tracker that has not yet reported anything.
 *
 * @property position last known position, or null before the first fix.
 * @property altitudeMslMeters last known altitude above mean sea level.
 * @property altitudeAglMeters altitude above ground level, i.e. relative to
 *   the altitude baseline (see [FlightTracker.resetAltitudeBaseline]).
 * @property maxAltitudeMslMeters highest MSL altitude seen so far.
 * @property maxAltitudeAglMeters highest altitude relative to the current
 *   baseline; derived from [maxAltitudeMslMeters] minus the baseline.
 * @property fixQuality GGA fix quality (0 = no fix, 1 = GPS, 2 = DGPS, ...).
 * @property satellitesInUse number of satellites used for the fix.
 * @property hdop horizontal dilution of precision.
 * @property speedKnots ground speed in knots.
 * @property courseDeg course over ground in degrees true.
 * @property lastSentenceAtMs time any sentence was successfully parsed —
 *   proof the radio link is alive, regardless of fix.
 * @property lastFixAtMs time of the last actual position update. Stays null
 *   for positions injected via [FlightTracker.restoreLastPosition] so the UI
 *   can tell live data from stale saved data.
 * @property path decimated ground track; see [FlightTracker] for the append
 *   and capping rules.
 */
data class RocketState(
    val position: GeoPoint? = null,
    val altitudeMslMeters: Double? = null,
    val altitudeAglMeters: Double? = null,
    val maxAltitudeMslMeters: Double? = null,
    val maxAltitudeAglMeters: Double? = null,
    val fixQuality: Int = 0,
    val satellitesInUse: Int? = null,
    val hdop: Double? = null,
    val speedKnots: Double? = null,
    val courseDeg: Double? = null,
    val lastSentenceAtMs: Long? = null,
    val lastFixAtMs: Long? = null,
    val path: List<TrackPoint> = emptyList(),
)

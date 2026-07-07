package io.github.mtnmanak.rocketlocator26.core.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical-earth geodesy: distance and bearing math used to guide the user
 * from their phone's position to the rocket's last reported position.
 *
 * All calculations use the great-circle (haversine) model on a sphere of
 * radius [EARTH_RADIUS_METERS]. Over recovery-scale distances (meters to a
 * few tens of kilometers) the spherical error versus the WGS-84 ellipsoid is
 * well under 0.5%, which is far below GPS fix error.
 */
object Geodesy {

    /** IUGG mean earth radius, in meters. */
    const val EARTH_RADIUS_METERS: Double = 6371008.8

    private const val DEG_TO_RAD: Double = PI / 180.0

    /**
     * Great-circle distance between [from] and [to] in meters, computed with
     * the haversine formula (numerically stable for the short distances
     * typical of rocket recovery, unlike the spherical law of cosines).
     *
     * Returns 0.0 for identical points. Always non-negative and finite for
     * finite inputs, including antipodal points and antimeridian crossings.
     */
    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = from.latitude * DEG_TO_RAD
        val lat2 = to.latitude * DEG_TO_RAD
        val dLat = (to.latitude - from.latitude) * DEG_TO_RAD
        val dLon = (to.longitude - from.longitude) * DEG_TO_RAD

        val sinDLat = sin(dLat / 2.0)
        val sinDLon = sin(dLon / 2.0)
        val a = (sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon)
            .coerceIn(0.0, 1.0) // guard rounding drift so sqrt(1 - a) never sees a negative
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Initial great-circle bearing from [from] to [to], in degrees clockwise
     * from true north, normalized to [0, 360).
     *
     * Degenerate cases: if the points are identical the result is 0.0 (there
     * is no direction to a point you are standing on); if [from] is exactly
     * at a pole every direction is equally "toward" the target and the result
     * is likewise not meaningful, though a value in [0, 360) is still
     * returned rather than NaN.
     */
    fun initialBearingDeg(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = from.latitude * DEG_TO_RAD
        val lat2 = to.latitude * DEG_TO_RAD
        val dLon = (to.longitude - from.longitude) * DEG_TO_RAD

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeDeg360(atan2(y, x) * 180.0 / PI)
    }

    /**
     * Signed turn from the user's current [headingDeg] to the target
     * [bearingDeg], in degrees, normalized to (-180, 180].
     *
     * Negative means the target is to the LEFT (turn left), positive to the
     * RIGHT. A target exactly behind the user yields +180.0, never -180.0.
     * Inputs may be any finite degree values (negative or > 360); they are
     * normalized internally.
     */
    fun relativeBearingDeg(headingDeg: Double, bearingDeg: Double): Double {
        val diff = normalizeDeg360(bearingDeg - headingDeg)
        return if (diff > 180.0) diff - 360.0 else diff
    }

    /**
     * Normalizes any finite angle in degrees to the range [0, 360).
     *
     * Handles negative angles ("-90" -> 270), multiples of full turns
     * ("720" -> 0), and never returns -0.0 or 360.0 (a tiny negative input
     * whose remainder rounds up to 360.0 is folded back to 0.0).
     */
    fun normalizeDeg360(deg: Double): Double {
        val r = deg % 360.0
        val shifted = if (r < 0.0) r + 360.0 else r
        // `r + 360.0` can round to exactly 360.0 for tiny negative r; fold it back.
        // `+ 0.0` converts a possible -0.0 remainder to +0.0.
        return if (shifted >= 360.0) shifted - 360.0 else shifted + 0.0
    }
}

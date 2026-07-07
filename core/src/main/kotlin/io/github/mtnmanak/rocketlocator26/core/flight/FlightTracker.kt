package io.github.mtnmanak.rocketlocator26.core.flight

import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import io.github.mtnmanak.rocketlocator26.core.geo.Geodesy
import io.github.mtnmanak.rocketlocator26.core.nmea.GgaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.GllSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.GsaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.GsvSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParseResult
import io.github.mtnmanak.rocketlocator26.core.nmea.RmcSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.VtgSentence

/**
 * Accumulates parsed NMEA telemetry into a [RocketState] snapshot.
 *
 * ## Update rules
 * - **GGA** with `fixQuality >= 1` and non-null lat/lon updates position,
 *   altitude, fix quality, satellite count, HDOP, and [RocketState.lastFixAtMs],
 *   and recomputes max altitudes. A GGA with `fixQuality == 0` (or with a fix
 *   claim but missing coordinates) only updates fix quality, satellite count,
 *   and [RocketState.lastSentenceAtMs] — it NEVER moves the position. The
 *   original app drew garbage track lines from no-fix packets; this one won't.
 * - **RMC** with a valid status and non-null lat/lon updates position, speed,
 *   course, and `lastFixAtMs`. An invalid RMC only proves the link is alive.
 * - **GLL** with a valid status and non-null lat/lon updates position.
 * - **VTG** updates speed and course only.
 * - **GSA/GSV** update `lastSentenceAtMs` only.
 * - Every [NmeaParseResult.Parsed] updates `lastSentenceAtMs`.
 *   [NmeaParseResult.Failed] and [NmeaParseResult.Unsupported] update nothing
 *   at all, not even timestamps.
 * - Null fields in an otherwise-accepted sentence never erase previously known
 *   values (e.g. a GGA without altitude keeps the last known altitude).
 *
 * ## Altitude baseline (AGL)
 * The first non-null GGA altitude automatically becomes the AGL baseline, so
 * AGL reads zero on the pad. [resetAltitudeBaseline] rebaselines to the
 * current altitude (useful after landing at a different elevation, or if the
 * tracker was powered up mid-transport). `altitudeAgl = msl - baseline` and
 * `maxAltitudeAgl = maxMsl - baseline`; both are recomputed on rebaseline.
 *
 * ## Path
 * A [TrackPoint] is appended on a position update only when the rocket has
 * moved more than [PATH_MIN_STEP_METERS] from the last path point (or the path
 * is empty), so GPS jitter on the pad does not balloon the track. When the
 * path is full ([maxPathPoints]), the point at index 1 — the oldest point
 * *after* the launch point — is dropped, so `path[0]` (the launch point) is
 * always preserved even on very long flights or drives back to the car.
 *
 * ## Threading
 * NOT thread-safe by design: it is meant to be fed from a single consumer
 * (the Bluetooth read loop). Synchronize externally if that ever changes.
 *
 * @param maxPathPoints maximum number of points retained in [RocketState.path].
 */
class FlightTracker(private val maxPathPoints: Int = 10_000) {

    /** Current immutable snapshot of the rocket's state. */
    var state: RocketState = RocketState()
        private set

    /** MSL altitude currently treated as AGL zero, or null if not yet set. */
    private var baselineMslMeters: Double? = null

    /**
     * Applies one parse result to the state.
     *
     * @param result output of the NMEA parser for one line.
     * @param nowMs receiver-local wall-clock time (epoch millis) of receipt.
     * @return the updated state snapshot (also available via [state]).
     */
    fun onResult(result: NmeaParseResult, nowMs: Long): RocketState {
        val parsed = result as? NmeaParseResult.Parsed ?: return state
        // Any successfully parsed sentence proves the link is alive.
        var next = state.copy(lastSentenceAtMs = nowMs)

        when (val sentence = parsed.sentence) {
            is GgaSentence -> next = applyGga(next, sentence, nowMs)
            is RmcSentence -> next = applyRmc(next, sentence, nowMs)
            is GllSentence -> next = applyGll(next, sentence, nowMs)
            is VtgSentence -> next = next.copy(
                speedKnots = sentence.speedKnots ?: next.speedKnots,
                courseDeg = sentence.courseTrueDeg ?: next.courseDeg,
            )
            is GsaSentence -> Unit // link-alive timestamp only
            is GsvSentence -> Unit // link-alive timestamp only
        }

        state = next
        return state
    }

    /**
     * Makes the current altitude the new AGL zero.
     *
     * Recomputes [RocketState.altitudeAglMeters] and
     * [RocketState.maxAltitudeAglMeters] against the new baseline
     * (max MSL is untouched). If no altitude is currently known, the baseline
     * is cleared so the next non-null GGA altitude re-establishes it.
     */
    fun resetAltitudeBaseline() {
        baselineMslMeters = state.altitudeMslMeters
        state = state.copy(
            altitudeAglMeters = aglOf(state.altitudeMslMeters),
            maxAltitudeAglMeters = aglOf(state.maxAltitudeMslMeters),
        )
    }

    /** Full reset to the initial state, including the altitude baseline. */
    fun reset() {
        baselineMslMeters = null
        state = RocketState()
    }

    /**
     * Restores a position saved from a previous run (app relaunch after the
     * phone died in the field, etc.).
     *
     * Deliberately does NOT touch [RocketState.lastFixAtMs] — this is stale
     * data and the UI must be able to see that no live fix has arrived yet.
     * Adds no path point and does not affect max altitudes or the baseline.
     *
     * @param point last saved rocket position.
     * @param altitudeMslMeters last saved MSL altitude, if any was known.
     */
    fun restoreLastPosition(point: GeoPoint, altitudeMslMeters: Double?) {
        state = state.copy(
            position = point,
            altitudeMslMeters = altitudeMslMeters ?: state.altitudeMslMeters,
            altitudeAglMeters = aglOf(altitudeMslMeters ?: state.altitudeMslMeters),
        )
    }

    private fun applyGga(current: RocketState, gga: GgaSentence, nowMs: Long): RocketState {
        val lat = gga.latitude
        val lon = gga.longitude
        if (gga.fixQuality < 1 || lat == null || lon == null) {
            // No usable fix: NEVER move the position from a no-fix sentence.
            return current.copy(
                fixQuality = gga.fixQuality,
                satellitesInUse = gga.satellitesInUse ?: current.satellitesInUse,
            )
        }

        val position = GeoPoint(lat, lon)
        val msl = gga.altitudeMslMeters ?: current.altitudeMslMeters
        // Baseline and max altitude derive ONLY from the sentence's own altitude,
        // never the carried-forward value: a stale altitude injected by
        // restoreLastPosition must not become the pad elevation or fake a max.
        if (baselineMslMeters == null && gga.altitudeMslMeters != null) {
            // First live altitude becomes the AGL baseline (pad elevation).
            baselineMslMeters = gga.altitudeMslMeters
        }
        val maxMsl = maxOfNullable(current.maxAltitudeMslMeters, gga.altitudeMslMeters)
        return current.copy(
            position = position,
            altitudeMslMeters = msl,
            altitudeAglMeters = aglOf(msl),
            maxAltitudeMslMeters = maxMsl,
            maxAltitudeAglMeters = aglOf(maxMsl),
            fixQuality = gga.fixQuality,
            satellitesInUse = gga.satellitesInUse ?: current.satellitesInUse,
            hdop = gga.hdop ?: current.hdop,
            lastFixAtMs = nowMs,
            path = appendToPath(current.path, position, msl, nowMs),
        )
    }

    private fun applyRmc(current: RocketState, rmc: RmcSentence, nowMs: Long): RocketState {
        val lat = rmc.latitude
        val lon = rmc.longitude
        if (!rmc.isValid || lat == null || lon == null) {
            // Invalid RMC: telemetry (lastSentenceAtMs) only.
            return current
        }
        val position = GeoPoint(lat, lon)
        return current.copy(
            position = position,
            speedKnots = rmc.speedKnots ?: current.speedKnots,
            courseDeg = rmc.courseDeg ?: current.courseDeg,
            lastFixAtMs = nowMs,
            path = appendToPath(current.path, position, current.altitudeMslMeters, nowMs),
        )
    }

    private fun applyGll(current: RocketState, gll: GllSentence, nowMs: Long): RocketState {
        val lat = gll.latitude
        val lon = gll.longitude
        if (!gll.isValid || lat == null || lon == null) {
            return current
        }
        val position = GeoPoint(lat, lon)
        return current.copy(
            position = position,
            lastFixAtMs = nowMs,
            path = appendToPath(current.path, position, current.altitudeMslMeters, nowMs),
        )
    }

    /**
     * Appends a path point if the rocket moved more than [PATH_MIN_STEP_METERS]
     * from the last path point (or the path is empty). When over capacity,
     * drops index 1 — the oldest point after the launch point — so `path[0]`
     * is always the launch point.
     */
    private fun appendToPath(
        path: List<TrackPoint>,
        position: GeoPoint,
        altitudeMslMeters: Double?,
        nowMs: Long,
    ): List<TrackPoint> {
        val last = path.lastOrNull()
        if (last != null && Geodesy.distanceMeters(last.point, position) <= PATH_MIN_STEP_METERS) {
            return path
        }
        val out = path.toMutableList()
        out.add(TrackPoint(position, altitudeMslMeters, nowMs))
        while (out.size > maxPathPoints && out.size > 1) {
            out.removeAt(1) // preserve path[0], the launch point
        }
        return out
    }

    /** AGL of [msl] against the current baseline, or null if either is unknown. */
    private fun aglOf(msl: Double?): Double? {
        val baseline = baselineMslMeters ?: return null
        return msl?.minus(baseline)
    }

    private fun maxOfNullable(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private companion object {
        /** Minimum movement, in meters, before a new path point is recorded. */
        const val PATH_MIN_STEP_METERS = 2.0
    }
}

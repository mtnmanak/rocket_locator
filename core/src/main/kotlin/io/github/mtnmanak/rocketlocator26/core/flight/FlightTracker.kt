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
import kotlin.math.abs

/**
 * Accumulates parsed NMEA telemetry into a [RocketState] snapshot.
 *
 * ## Update rules
 * - **GGA** with measured `fixQuality` 1–5 and non-null lat/lon updates position,
 *   plausible altitude, and [RocketState.lastFixAtMs]. Every GGA updates receiver
 *   status (fix quality, satellite count, HDOP) and [RocketState.lastSentenceAtMs].
 *   Rejected qualities (including dead reckoning 6, manual 7, simulation 8)
 *   or missing coordinates NEVER move position, altitude, path or fix time. The
 *   original app drew garbage track lines from no-fix packets; this one won't.
 * - **RMC/GLL** require valid status, non-null lat/lon, and measured mode
 *   A/D/F/P/R or an absent/empty legacy mode. They update position and fix time;
 *   accepted RMC also updates speed/course. Other modes only prove link health.
 * - **VTG** updates speed and course only.
 * - **GSA/GSV** update `lastSentenceAtMs` only.
 * - Every [NmeaParseResult.Parsed] updates `lastSentenceAtMs`.
 *   [NmeaParseResult.Failed] and [NmeaParseResult.Unsupported] update nothing
 *   at all, not even timestamps.
 * - Null fields in an otherwise-accepted sentence never erase previously known
 *   values (e.g. a GGA without altitude keeps the last known altitude).
 *
 * ## Altitude baseline (AGL)
 * The first accepted GGA altitude automatically becomes the AGL baseline, so
 * AGL reads zero on the pad. [resetAltitudeBaseline] rebaselines to the
 * current altitude (useful after landing at a different elevation, or if the
 * tracker was powered up mid-transport). `altitudeAgl = msl - baseline` and
 * `maxAltitudeAgl = maxMsl - baseline`; both are recomputed on rebaseline.
 * Altitudes must be finite and within −1,000..120,000 m MSL. Against the last
 * accepted altitude, allow at most `1,500 m/s × max(elapsed seconds, 1) + 100 m`,
 * using receipt times from [onResult]. These deliberately generous bounds allow
 * sounding-rocket ceilings and fast climbs; COCOM-limited modules stop well below.
 * Rejected altitudes affect neither baseline nor maximum, and leave horizontal
 * fixes usable. Saved altitudes are not live samples for the rate gate.
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
class FlightTracker(
    private val maxPathPoints: Int = 10_000,
) {
    /** Current immutable snapshot of the rocket's state. */
    var state: RocketState = RocketState()
        private set

    /** MSL altitude currently treated as AGL zero, or null if not yet set. */
    private var baselineMslMeters: Double? = null

    private var lastAcceptedAltitudeMslMeters: Double? = null
    private var lastAcceptedAltitudeAtMs: Long? = null

    /**
     * Applies one parse result to the state.
     *
     * @param result output of the NMEA parser for one line.
     * @param nowMs receipt time in milliseconds from the caller's clock. The app passes a MONOTONIC clock
     *   (`SystemClock.elapsedRealtime`), so fix ages and the altitude rate gate are immune to wall-clock jumps;
     *   it is not an epoch time.
     * @return the updated state snapshot (also available via [state]).
     */
    fun onResult(
        result: NmeaParseResult,
        nowMs: Long,
    ): RocketState {
        val parsed = result as? NmeaParseResult.Parsed ?: return state
        // Any successfully parsed sentence proves the link is alive.
        var next = state.copy(lastSentenceAtMs = nowMs)

        when (val sentence = parsed.sentence) {
            is GgaSentence -> {
                next = applyGga(next, sentence, nowMs)
            }

            is RmcSentence -> {
                next = applyRmc(next, sentence, nowMs)
            }

            is GllSentence -> {
                next = applyGll(next, sentence, nowMs)
            }

            is VtgSentence -> {
                next =
                    next.copy(
                        speedKnots = sentence.speedKnots ?: next.speedKnots,
                        courseDeg = sentence.courseTrueDeg ?: next.courseDeg,
                    )
            }

            // GSA/GSV only prove the link is alive (lastSentenceAtMs, set above).
            is GsaSentence, is GsvSentence -> {}
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
        state =
            state.copy(
                altitudeAglMeters = aglOf(state.altitudeMslMeters),
                maxAltitudeAglMeters = aglOf(state.maxAltitudeMslMeters),
            )
    }

    /** Full reset to the initial state, including the altitude baseline. */
    fun reset() {
        baselineMslMeters = null
        lastAcceptedAltitudeMslMeters = null
        lastAcceptedAltitudeAtMs = null
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
    fun restoreLastPosition(
        point: GeoPoint,
        altitudeMslMeters: Double?,
    ) {
        state =
            state.copy(
                position = point,
                altitudeMslMeters = altitudeMslMeters ?: state.altitudeMslMeters,
                altitudeAglMeters = aglOf(altitudeMslMeters ?: state.altitudeMslMeters),
            )
    }

    private fun applyGga(
        current: RocketState,
        gga: GgaSentence,
        nowMs: Long,
    ): RocketState {
        val lat = gga.latitude
        val lon = gga.longitude
        if (gga.fixQuality !in 1..5 || lat == null || lon == null) {
            // No usable fix: NEVER move the position from a no-fix sentence.
            return current.copy(
                fixQuality = gga.fixQuality,
                satellitesInUse = gga.satellitesInUse ?: current.satellitesInUse,
                hdop = gga.hdop ?: current.hdop,
            )
        }

        val position = GeoPoint(lat, lon)
        val acceptedAltitude = acceptAltitude(gga.altitudeMslMeters, nowMs)
        val msl = acceptedAltitude ?: current.altitudeMslMeters
        // Baseline and max altitude derive ONLY from the sentence's own altitude,
        // never the carried-forward value: a stale altitude injected by
        // restoreLastPosition must not become the pad elevation or fake a max.
        if (baselineMslMeters == null && acceptedAltitude != null) {
            // First live altitude becomes the AGL baseline (pad elevation).
            baselineMslMeters = acceptedAltitude
        }
        val maxMsl = maxOfNullable(current.maxAltitudeMslMeters, acceptedAltitude)
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

    private fun applyRmc(
        current: RocketState,
        rmc: RmcSentence,
        nowMs: Long,
    ): RocketState {
        val lat = rmc.latitude
        val lon = rmc.longitude
        if (!rmc.isValid || !isMeasuredMode(rmc.mode) || lat == null || lon == null) {
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

    private fun applyGll(
        current: RocketState,
        gll: GllSentence,
        nowMs: Long,
    ): RocketState {
        val lat = gll.latitude
        val lon = gll.longitude
        if (!gll.isValid || !isMeasuredMode(gll.mode) || lat == null || lon == null) {
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

    private fun isMeasuredMode(mode: Char?): Boolean = mode == null || mode in "ADFPR"

    /** Returns only accepted live samples; rejected samples never advance the gate. */
    private fun acceptAltitude(
        altitude: Double?,
        nowMs: Long,
    ): Double? {
        if (altitude == null || !altitude.isFinite() || altitude !in -1_000.0..120_000.0) return null
        val previous = lastAcceptedAltitudeMslMeters
        val previousAtMs = lastAcceptedAltitudeAtMs
        if (previous != null && previousAtMs != null) {
            // Convert before subtraction to avoid Long overflow; backward clocks use the 1 s floor.
            val elapsedSeconds = ((nowMs.toDouble() - previousAtMs.toDouble()) / 1_000.0).coerceAtLeast(1.0)
            if (abs(altitude - previous) > 1_500.0 * elapsedSeconds + 100.0) return null
        }
        lastAcceptedAltitudeMslMeters = altitude
        lastAcceptedAltitudeAtMs = nowMs
        return altitude
    }

    private fun maxOfNullable(
        a: Double?,
        b: Double?,
    ): Double? =
        when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }

    private companion object {
        /** Minimum movement, in meters, before a new path point is recorded. */
        const val PATH_MIN_STEP_METERS = 2.0
    }
}

package io.github.mtnmanak.rocketlocator26.core.sim

import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * One tick of simulated receiver output.
 *
 * @property timeOffsetMs milliseconds since the start of the simulation at
 *   which the [sentences] should be delivered.
 * @property sentences the raw NMEA sentences to deliver at this tick, in order.
 */
data class SimulatedSample(val timeOffsetMs: Long, val sentences: List<String>)

/**
 * Parameters describing a synthetic rocket flight.
 *
 * The simulated rocket sits on the pad at [launch], ascends vertically to
 * [apogeeAglMeters] above ground over [ascentSeconds], then descends over
 * [descentSeconds] while drifting [driftDistanceMeters] along
 * [driftBearingDeg], landing back at [launchAltitudeMslMeters].
 *
 * @property launch launch-pad position.
 * @property launchAltitudeMslMeters altitude of the pad above mean sea level.
 * @property apogeeAglMeters peak altitude above ground level.
 * @property ascentSeconds duration of the ascent phase; must be positive.
 * @property descentSeconds duration of the descent phase; must be positive.
 * @property driftBearingDeg direction the rocket drifts during descent
 *   (degrees true, any value; normalized to 0..360).
 * @property driftDistanceMeters total ground drift at landing; non-negative.
 * @property sampleHz samples emitted per simulated second; at least 1.
 */
data class FlightParams(
    val launch: GeoPoint,
    val launchAltitudeMslMeters: Double,
    val apogeeAglMeters: Double,
    val ascentSeconds: Double,
    val descentSeconds: Double,
    val driftBearingDeg: Double,
    val driftDistanceMeters: Double,
    val sampleHz: Int = 1,
)

/**
 * Deterministic synthetic flight generator.
 *
 * Produces a finite stream of [SimulatedSample]s, each carrying a GGA and an
 * RMC sentence (talker `GP`) with correct NMEA checksums, encoding the
 * interpolated position and altitude at that instant. The flight has four
 * phases:
 *
 * 1. **Pad** — 5 samples stationary at the launch point.
 * 2. **Ascent** — sine-eased climb to apogee; ground track stays at the pad.
 * 3. **Descent** — sine-eased altitude decay back to pad altitude while the
 *    ground position drifts linearly along the drift bearing.
 * 4. **Landed** — 10 samples stationary at the landing point.
 *
 * There is no randomness and no clock access; the same [FlightParams] always
 * yield byte-identical output.
 */
class SyntheticFlight(private val params: FlightParams) {

    init {
        require(params.sampleHz in 1..1000) { "sampleHz must be in 1..1000, was ${params.sampleHz}" }
        require(params.ascentSeconds > 0.0 && params.ascentSeconds.isFinite()) {
            "ascentSeconds must be finite and > 0, was ${params.ascentSeconds}"
        }
        require(params.descentSeconds > 0.0 && params.descentSeconds.isFinite()) {
            "descentSeconds must be finite and > 0, was ${params.descentSeconds}"
        }
        require(params.apogeeAglMeters >= 0.0 && params.apogeeAglMeters.isFinite()) {
            "apogeeAglMeters must be finite and >= 0, was ${params.apogeeAglMeters}"
        }
        require(params.driftDistanceMeters >= 0.0 && params.driftDistanceMeters.isFinite()) {
            "driftDistanceMeters must be finite and >= 0, was ${params.driftDistanceMeters}"
        }
        // NaN/Infinity here would otherwise surface as a cryptic roundToLong
        // crash deep inside sentence encoding, far from the bad parameter.
        require(params.driftBearingDeg.isFinite()) {
            "driftBearingDeg must be finite, was ${params.driftBearingDeg}"
        }
        require(params.launchAltitudeMslMeters.isFinite()) {
            "launchAltitudeMslMeters must be finite, was ${params.launchAltitudeMslMeters}"
        }
        require(params.launch.latitude.isFinite() && params.launch.longitude.isFinite()) {
            "launch coordinates must be finite, were ${params.launch}"
        }
    }

    /**
     * Generates the full flight as a finite, replayable sequence.
     *
     * Sample count is `5 + round(ascentSeconds * sampleHz) +
     * round(descentSeconds * sampleHz) + 10` (each flight phase contributes at
     * least one sample). Sample `i` is at offset `i * 1000 / sampleHz` ms
     * (rounded down per sample, so offsets stay anchored to the true rate with
     * no cumulative drift), starting at 0.
     */
    fun samples(): Sequence<SimulatedSample> {
        val ascentCount = max(1, (params.ascentSeconds * params.sampleHz).roundToInt())
        val descentCount = max(1, (params.descentSeconds * params.sampleHz).roundToInt())
        val landing = destination(params.launch, params.driftBearingDeg, params.driftDistanceMeters)
        val courseDeg = normalizeBearing(params.driftBearingDeg)
        val descentSpeedKnots = params.driftDistanceMeters / params.descentSeconds * MPS_TO_KNOTS

        val samples = ArrayList<SimulatedSample>(PAD_SAMPLES + ascentCount + descentCount + LANDED_SAMPLES)
        var index = 0

        fun emit(position: GeoPoint, altitudeMsl: Double, speedKnots: Double, course: Double) {
            // Multiply before dividing: per-sample rounding without drift.
            val offsetMs = index * 1000L / params.sampleHz
            samples += SimulatedSample(
                timeOffsetMs = offsetMs,
                sentences = listOf(
                    gga(offsetMs, position, altitudeMsl),
                    rmc(offsetMs, position, speedKnots, course),
                ),
            )
            index++
        }

        // Phase 1: on the pad.
        repeat(PAD_SAMPLES) {
            emit(params.launch, params.launchAltitudeMslMeters, 0.0, 0.0)
        }
        // Phase 2: ascent. Sine-eased so the climb starts and ends smoothly;
        // fraction k/ascentCount guarantees the final ascent sample is exactly
        // at apogee regardless of duration rounding.
        for (k in 1..ascentCount) {
            val f = k.toDouble() / ascentCount
            val altitude = params.launchAltitudeMslMeters + params.apogeeAglMeters * 0.5 * (1.0 - cos(PI * f))
            emit(params.launch, altitude, 0.0, 0.0)
        }
        // Phase 3: descent. Altitude eases back to pad level; ground position
        // drifts linearly, reaching the landing point exactly at f = 1.
        for (k in 1..descentCount) {
            val f = k.toDouble() / descentCount
            val altitude = params.launchAltitudeMslMeters + params.apogeeAglMeters * 0.5 * (1.0 + cos(PI * f))
            val position = destination(params.launch, params.driftBearingDeg, params.driftDistanceMeters * f)
            emit(position, altitude, descentSpeedKnots, courseDeg)
        }
        // Phase 4: down and stationary.
        repeat(LANDED_SAMPLES) {
            emit(landing, params.launchAltitudeMslMeters, 0.0, 0.0)
        }
        return samples.asSequence()
    }

    // --- NMEA sentence builders --------------------------------------------

    private fun gga(offsetMs: Long, position: GeoPoint, altitudeMsl: Double): String {
        val lat = encodeLatitude(position.latitude)
        val lon = encodeLongitude(position.longitude)
        val body = "GPGGA,${timeUtc(offsetMs)},${lat.first},${lat.second}," +
            "${lon.first},${lon.second},1,08,1.0," +
            "${formatFixed(altitudeMsl, 1, 1)},M,0.0,M,,"
        return frame(body)
    }

    private fun rmc(offsetMs: Long, position: GeoPoint, speedKnots: Double, courseDeg: Double): String {
        val lat = encodeLatitude(position.latitude)
        val lon = encodeLongitude(position.longitude)
        val body = "GPRMC,${timeUtc(offsetMs)},A,${lat.first},${lat.second}," +
            "${lon.first},${lon.second}," +
            "${formatFixed(speedKnots, 3, 1)},${formatFixed(courseDeg, 3, 1)},$RMC_DATE,,"
        return frame(body)
    }

    /** Frames a sentence body with `$`, `*`, and the XOR checksum of [body]. */
    private fun frame(body: String): String {
        var checksum = 0
        for (c in body) checksum = checksum xor c.code
        return "\$$body*${HEX[(checksum ushr 4) and 0xF]}${HEX[checksum and 0xF]}"
    }

    // --- Field encoding ------------------------------------------------------

    /** UTC time `hhmmss` starting at 12:00:00 and advancing with the sample clock. */
    private fun timeUtc(offsetMs: Long): String {
        val totalSeconds = (START_TIME_SECONDS + offsetMs / 1000L) % 86_400L
        val h = totalSeconds / 3_600L
        val m = (totalSeconds % 3_600L) / 60L
        val s = totalSeconds % 60L
        return pad(h, 2) + pad(m, 2) + pad(s, 2)
    }

    /** Latitude as `ddmm.mmmm` plus hemisphere character. */
    private fun encodeLatitude(latitude: Double): Pair<String, Char> =
        encodeCoordinate(abs(latitude), 2) to (if (latitude >= 0.0) 'N' else 'S')

    /** Longitude as `dddmm.mmmm` plus hemisphere character. */
    private fun encodeLongitude(longitude: Double): Pair<String, Char> =
        encodeCoordinate(abs(longitude), 3) to (if (longitude >= 0.0) 'E' else 'W')

    /**
     * Encodes an absolute coordinate as zero-padded degrees plus `mm.mmmm`
     * minutes. Rounding is done in units of 1e-4 minutes so a value like
     * 59.99996 minutes carries into the degrees field instead of printing 60.
     */
    private fun encodeCoordinate(absDegrees: Double, degreeDigits: Int): String {
        val totalMinutesE4 = (absDegrees * 60.0 * 10_000.0).roundToLong()
        val degrees = totalMinutesE4 / MINUTES_E4_PER_DEGREE
        val minutesE4 = totalMinutesE4 % MINUTES_E4_PER_DEGREE
        return pad(degrees, degreeDigits) +
            pad(minutesE4 / 10_000L, 2) + "." + pad(minutesE4 % 10_000L, 4)
    }

    /**
     * Formats [value] with exactly [fracDigits] decimals and at least
     * [intDigits] integer digits (zero-padded), locale-independently.
     */
    private fun formatFixed(value: Double, intDigits: Int, fracDigits: Int): String {
        var pow = 1L
        repeat(fracDigits) { pow *= 10L }
        val scaled = (abs(value) * pow).roundToLong()
        val sign = if (value < 0.0 && scaled > 0L) "-" else ""
        return sign + pad(scaled / pow, intDigits) + "." + pad(scaled % pow, fracDigits)
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')

    // --- Geodesy -------------------------------------------------------------

    /**
     * Destination point at [distanceMeters] along the great circle from
     * [start] on initial bearing [bearingDeg] (standard spherical formula,
     * Earth radius [EARTH_RADIUS_METERS]).
     */
    private fun destination(start: GeoPoint, bearingDeg: Double, distanceMeters: Double): GeoPoint {
        if (distanceMeters == 0.0) return start
        val delta = distanceMeters / EARTH_RADIUS_METERS
        val theta = bearingDeg * DEG_TO_RAD
        val phi1 = start.latitude * DEG_TO_RAD
        val lambda1 = start.longitude * DEG_TO_RAD
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2),
        )
        val lonDeg = ((lambda2 / DEG_TO_RAD + 540.0) % 360.0) - 180.0
        return GeoPoint(phi2 / DEG_TO_RAD, lonDeg)
    }

    private fun normalizeBearing(bearingDeg: Double): Double =
        ((bearingDeg % 360.0) + 360.0) % 360.0

    private companion object {
        const val PAD_SAMPLES = 5
        const val LANDED_SAMPLES = 10
        const val START_TIME_SECONDS = 12L * 3_600L // 120000 UTC
        const val RMC_DATE = "010126"
        const val EARTH_RADIUS_METERS = 6_371_008.8
        const val DEG_TO_RAD = PI / 180.0
        const val MPS_TO_KNOTS = 3_600.0 / 1_852.0
        const val MINUTES_E4_PER_DEGREE = 600_000L
        const val HEX = "0123456789ABCDEF"
    }
}

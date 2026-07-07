package io.github.mtnmanak.rocketlocator26.core.sim

import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import io.github.mtnmanak.rocketlocator26.core.nmea.GgaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParseResult
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParser
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaSentence
import io.github.mtnmanak.rocketlocator26.core.nmea.RmcSentence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Round-trip tests: every sentence the simulator emits must survive the real
 * [NmeaParser] and decode back to the physics the simulator claims. A silent
 * encoding error here would send someone walking to the wrong field.
 */
class SyntheticFlightTest {

    private val parser = NmeaParser()

    private val launch = GeoPoint(48.1173, 11.5167)
    private val defaultParams = FlightParams(
        launch = launch,
        launchAltitudeMslMeters = 545.4,
        apogeeAglMeters = 800.0,
        ascentSeconds = 20.0,
        descentSeconds = 60.0,
        driftBearingDeg = 84.4,
        driftDistanceMeters = 400.0,
        sampleHz = 1,
    )

    // Phase layout for defaultParams at 1 Hz: 5 pad + 20 ascent + 60 descent + 10 landed.
    private val padRange = 0 until 5
    private val ascentRange = 5 until 25
    private val descentRange = 25 until 85
    private val landedRange = 85 until 95

    private val samples: List<SimulatedSample> by lazy {
        SyntheticFlight(defaultParams).samples().toList()
    }

    // --- Round-trip validity -------------------------------------------------

    @Test
    fun everyGeneratedSentenceParsesAsParsed() {
        assertTrue(samples.isNotEmpty(), "simulator emitted no samples")
        for (sample in samples) {
            for (raw in sample.sentences) {
                val result = parser.parse(raw)
                assertTrue(
                    result is NmeaParseResult.Parsed,
                    "expected Parsed but got $result for: $raw",
                )
            }
        }
    }

    @Test
    fun everySentenceCarriesACorrectChecksum() {
        for (sample in samples) {
            for (raw in sample.sentences) {
                assertTrue(raw.startsWith("$"), "missing '$' framing: $raw")
                val star = raw.indexOf('*')
                assertTrue(star > 0, "missing '*' framing: $raw")
                val body = raw.substring(1, star)
                val declared = raw.substring(star + 1)
                assertEquals(2, declared.length, "checksum must be two hex digits: $raw")
                val computed = body.fold(0) { acc, c -> acc xor c.code }
                val expected = computed.toString(16).uppercase().padStart(2, '0')
                assertEquals(expected, declared, "checksum mismatch on: $raw")
            }
        }
    }

    @Test
    fun eachSampleEmitsGgaThenRmcWithTalkerGp() {
        for (sample in samples) {
            assertEquals(2, sample.sentences.size, "each sample must carry GGA then RMC")
            val first = parseOrFail(sample.sentences[0])
            val second = parseOrFail(sample.sentences[1])
            assertTrue(first is GgaSentence, "first sentence must be GGA, was $first")
            assertTrue(second is RmcSentence, "second sentence must be RMC, was $second")
            assertEquals("GP", first.talker)
            assertEquals("GP", second.talker)
        }
    }

    // --- Position accuracy ---------------------------------------------------

    @Test
    fun firstSampleDecodesToLaunchPoint() {
        val gga = gga(samples.first())
        val decoded = position(gga)
        val error = distanceMeters(launch, decoded)
        assertTrue(error < 0.7, "first sample $error m from launch (limit 0.7 m)")
        assertEquals(defaultParams.launchAltitudeMslMeters, gga.altitudeMslMeters!!, 0.06)
    }

    @Test
    fun lastSampleDecodesToExpectedLandingPoint() {
        val expectedLanding = destination(
            launch,
            defaultParams.driftBearingDeg,
            defaultParams.driftDistanceMeters,
        )
        val decoded = position(gga(samples.last()))
        val error = distanceMeters(expectedLanding, decoded)
        val limit = 0.7 + 0.01 * defaultParams.driftDistanceMeters
        assertTrue(error < limit, "landing $error m from expected point (limit $limit m)")
    }

    @Test
    fun maxDecodedAltitudeReachesApogee() {
        val expectedApogeeMsl =
            defaultParams.launchAltitudeMslMeters + defaultParams.apogeeAglMeters
        val maxAltitude = samples.maxOf { gga(it).altitudeMslMeters!! }
        assertTrue(
            abs(maxAltitude - expectedApogeeMsl) < 1.0,
            "max altitude $maxAltitude, expected $expectedApogeeMsl +/- 1 m",
        )
    }

    @Test
    fun ggaAndRmcAgreeOnPositionInEverySample() {
        for (sample in samples) {
            val gga = gga(sample)
            val rmc = rmc(sample)
            assertEquals(gga.latitude!!, rmc.latitude!!, 0.0, "lat disagreement in $sample")
            assertEquals(gga.longitude!!, rmc.longitude!!, 0.0, "lon disagreement in $sample")
        }
    }

    // --- Phase behavior ------------------------------------------------------

    @Test
    fun sampleCountMatchesPhases() {
        // 5 pad + round(20 * 1) ascent + round(60 * 1) descent + 10 landed.
        assertEquals(95, samples.size)
    }

    @Test
    fun timeOffsetsStrictlyIncreaseAtSampleCadence() {
        for (i in 1 until samples.size) {
            assertTrue(
                samples[i].timeOffsetMs > samples[i - 1].timeOffsetMs,
                "timeOffsetMs not strictly increasing at index $i",
            )
            assertEquals(1000L, samples[i].timeOffsetMs - samples[i - 1].timeOffsetMs)
        }
        assertEquals(0L, samples.first().timeOffsetMs)
    }

    @Test
    fun padPhaseIsStationaryAtLaunch() {
        for (i in padRange) {
            val gga = gga(samples[i])
            val rmc = rmc(samples[i])
            assertTrue(distanceMeters(launch, position(gga)) < 0.7, "pad sample $i moved")
            assertEquals(defaultParams.launchAltitudeMslMeters, gga.altitudeMslMeters!!, 0.06)
            assertEquals(0.0, rmc.speedKnots!!, 0.0, "pad sample $i has ground speed")
            assertEquals(0.0, rmc.courseDeg!!, 0.0)
        }
    }

    @Test
    fun ascentGroundTrackStaysAtLaunchAndAltitudeClimbsMonotonically() {
        var previousAltitude = defaultParams.launchAltitudeMslMeters - 0.001
        for (i in ascentRange) {
            val gga = gga(samples[i])
            assertTrue(
                distanceMeters(launch, position(gga)) < 0.7,
                "ascent sample $i drifted horizontally",
            )
            val altitude = gga.altitudeMslMeters!!
            assertTrue(altitude > previousAltitude, "ascent altitude not increasing at $i")
            previousAltitude = altitude
            assertEquals(0.0, rmc(samples[i]).speedKnots!!, 0.0)
        }
    }

    @Test
    fun descentReportsDriftBearingAndActualGroundSpeed() {
        val expectedKnots =
            defaultParams.driftDistanceMeters / defaultParams.descentSeconds * (3600.0 / 1852.0)
        for (i in descentRange) {
            val rmc = rmc(samples[i])
            assertEquals(defaultParams.driftBearingDeg, rmc.courseDeg!!, 1e-6, "course at $i")
            // 0.05 knots quantization from the one-decimal NMEA speed field.
            assertEquals(expectedKnots, rmc.speedKnots!!, 0.06, "speed at $i")
        }
    }

    @Test
    fun descentDriftAccumulatesMonotonicallyAndAltitudeFalls() {
        var previousDistance = -1.0
        var previousAltitude = Double.MAX_VALUE
        for (i in descentRange) {
            val gga = gga(samples[i])
            val distance = distanceMeters(launch, position(gga))
            assertTrue(distance > previousDistance, "drift not accumulating at $i")
            previousDistance = distance
            val altitude = gga.altitudeMslMeters!!
            assertTrue(altitude < previousAltitude, "descent altitude not falling at $i")
            previousAltitude = altitude
        }
        // Last descent sample is on the ground at the full drift distance.
        assertEquals(defaultParams.driftDistanceMeters, previousDistance, 1.0)
        assertEquals(defaultParams.launchAltitudeMslMeters, previousAltitude, 0.06)
    }

    @Test
    fun landedPhaseIsStationaryAtLandingSite() {
        val landing = destination(
            launch,
            defaultParams.driftBearingDeg,
            defaultParams.driftDistanceMeters,
        )
        assertEquals(10, landedRange.count())
        for (i in landedRange) {
            val gga = gga(samples[i])
            val rmc = rmc(samples[i])
            assertTrue(distanceMeters(landing, position(gga)) < 0.7, "landed sample $i moved")
            assertEquals(defaultParams.launchAltitudeMslMeters, gga.altitudeMslMeters!!, 0.06)
            assertEquals(0.0, rmc.speedKnots!!, 0.0)
            assertEquals(0.0, rmc.courseDeg!!, 0.0)
        }
    }

    // --- Fixed fields ----------------------------------------------------------

    @Test
    fun ggaReportsFixQualitySatellitesAndHdop() {
        for (sample in samples) {
            val gga = gga(sample)
            assertEquals(1, gga.fixQuality)
            assertEquals(8, gga.satellitesInUse)
            assertEquals(1.0, gga.hdop!!, 0.0)
        }
    }

    @Test
    fun rmcReportsFixedDateAndActiveStatus() {
        for (sample in samples) {
            val rmc = rmc(sample)
            assertTrue(rmc.isValid, "RMC status must be A (active)")
            assertEquals("010126", rmc.date)
        }
    }

    @Test
    fun utcTimeStartsAtNoonAndAdvancesWithSampleClock() {
        assertEquals("120000", gga(samples[0]).timeUtc)
        assertEquals("120001", gga(samples[1]).timeUtc)
        assertEquals("120059", gga(samples[59]).timeUtc)
        assertEquals("120100", gga(samples[60]).timeUtc)
        assertEquals("120134", gga(samples[94]).timeUtc)
        for (sample in samples) {
            assertEquals(gga(sample).timeUtc, rmc(sample).timeUtc, "GGA/RMC time disagreement")
        }
    }

    // --- Encoding edge cases -----------------------------------------------------

    @Test
    fun southernAndWesternHemispheresEncodeAndRoundTrip() {
        val southWest = GeoPoint(-33.8688, -70.6693)
        val flight = SyntheticFlight(defaultParams.copy(launch = southWest))
        val first = flight.samples().first()
        val rawGga = first.sentences[0]
        assertTrue(rawGga.contains(",S,"), "missing southern hemisphere flag: $rawGga")
        assertTrue(rawGga.contains(",W,"), "missing western hemisphere flag: $rawGga")
        val decoded = position(parseOrFail(rawGga) as GgaSentence)
        assertTrue(decoded.latitude < 0.0 && decoded.longitude < 0.0)
        assertTrue(
            distanceMeters(southWest, decoded) < 0.7,
            "south-west round trip error too large",
        )
    }

    @Test
    fun coordinateDegreesAreZeroPaddedTwoForLatThreeForLon() {
        // 5.25 deg = 5 deg 15.0000 min; 8.5 deg = 8 deg 30.0000 min.
        val nearEquator = GeoPoint(5.25, 8.5)
        val flight = SyntheticFlight(defaultParams.copy(launch = nearEquator))
        val rawGga = flight.samples().first().sentences[0]
        assertTrue(rawGga.contains(",0515.0000,N,"), "lat not ddmm.mmmm zero-padded: $rawGga")
        assertTrue(rawGga.contains(",00830.0000,E,"), "lon not dddmm.mmmm zero-padded: $rawGga")
    }

    @Test
    fun higherSampleRateScalesCountsAndCadence() {
        val flight = SyntheticFlight(defaultParams.copy(sampleHz = 2))
        val fast = flight.samples().toList()
        // 5 pad + round(20 * 2) ascent + round(60 * 2) descent + 10 landed.
        assertEquals(175, fast.size)
        for (i in 1 until fast.size) {
            assertEquals(500L, fast[i].timeOffsetMs - fast[i - 1].timeOffsetMs)
            assertTrue(fast[i].timeOffsetMs > fast[i - 1].timeOffsetMs)
        }
    }

    @Test
    fun fractionalPhaseDurationsStillReachApogeeExactly() {
        val params = defaultParams.copy(ascentSeconds = 2.4, descentSeconds = 3.6)
        val short = SyntheticFlight(params).samples().toList()
        // 5 pad + round(2.4) ascent + round(3.6) descent + 10 landed.
        assertEquals(5 + 2 + 4 + 10, short.size)
        val expectedApogeeMsl = params.launchAltitudeMslMeters + params.apogeeAglMeters
        val maxAltitude = short.maxOf { gga(it).altitudeMslMeters!! }
        assertTrue(
            abs(maxAltitude - expectedApogeeMsl) < 1.0,
            "max altitude $maxAltitude, expected $expectedApogeeMsl +/- 1 m",
        )
    }

    @Test
    fun zeroDriftLandsBackAtLaunchWithZeroSpeed() {
        val flight = SyntheticFlight(defaultParams.copy(driftDistanceMeters = 0.0))
        val still = flight.samples().toList()
        assertTrue(distanceMeters(launch, position(gga(still.last()))) < 0.7)
        for (sample in still) {
            assertEquals(0.0, rmc(sample).speedKnots!!, 0.0)
        }
    }

    @Test
    fun negativeDriftBearingIsNormalizedAndDriftsWest() {
        val flight = SyntheticFlight(
            defaultParams.copy(driftBearingDeg = -90.0, driftDistanceMeters = 200.0),
        )
        val westward = flight.samples().toList()
        val descentSample = westward[30] // mid-descent for the default phase layout
        assertEquals(270.0, rmc(descentSample).courseDeg!!, 1e-6)
        val landingLon = position(gga(westward.last())).longitude
        assertTrue(landingLon < launch.longitude, "bearing 270 must decrease longitude")
    }

    @Test
    fun outputIsDeterministicAndReplayable() {
        val flight = SyntheticFlight(defaultParams)
        assertEquals(flight.samples().toList(), flight.samples().toList())
        assertEquals(samples, SyntheticFlight(defaultParams).samples().toList())
    }

    // --- Helpers -------------------------------------------------------------

    private fun parseOrFail(raw: String): NmeaSentence {
        val result = parser.parse(raw)
        assertTrue(result is NmeaParseResult.Parsed, "expected Parsed but got $result for: $raw")
        return (result as NmeaParseResult.Parsed).sentence
    }

    private fun gga(sample: SimulatedSample): GgaSentence =
        parseOrFail(sample.sentences[0]) as GgaSentence

    private fun rmc(sample: SimulatedSample): RmcSentence =
        parseOrFail(sample.sentences[1]) as RmcSentence

    private fun position(gga: GgaSentence): GeoPoint {
        assertNotNull(gga.latitude, "GGA latitude decoded to null")
        assertNotNull(gga.longitude, "GGA longitude decoded to null")
        return GeoPoint(gga.latitude!!, gga.longitude!!)
    }

    /** Haversine great-circle distance, Earth radius 6371008.8 m. */
    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.latitude - a.latitude) * DEG_TO_RAD
        val dLon = (b.longitude - a.longitude) * DEG_TO_RAD
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.latitude * DEG_TO_RAD) * cos(b.latitude * DEG_TO_RAD) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(h))
    }

    /** Standard spherical destination formula — the same one the simulator must use. */
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

    @Test
    fun `sampleHz not dividing 1000 keeps offsets anchored without cumulative drift`() {
        val flight = SyntheticFlight(defaultParams.copy(sampleHz = 3))
        val offsets = flight.samples().map { it.timeOffsetMs }.toList()

        // Sample i sits at exactly i * 1000 / 3 ms (per-sample rounding):
        offsets.forEachIndexed { i, offset ->
            assertEquals(i * 1000L / 3, offset, "sample $i drifted")
        }
    }

    @Test
    fun `rejects sampleHz above 1000 which would collapse the sample clock`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFlight(defaultParams.copy(sampleHz = 1024))
        }
    }

    @Test
    fun `rejects non-finite params at construction instead of crashing mid-encoding`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFlight(defaultParams.copy(driftBearingDeg = Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFlight(defaultParams.copy(launchAltitudeMslMeters = Double.POSITIVE_INFINITY))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFlight(defaultParams.copy(launch = GeoPoint(Double.NaN, 11.5167)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFlight(defaultParams.copy(ascentSeconds = Double.POSITIVE_INFINITY))
        }
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
        const val DEG_TO_RAD = PI / 180.0
    }
}

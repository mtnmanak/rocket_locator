package io.github.mtnmanak.rocketlocator26.core.nmea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NmeaParserStrictnessTest {
    private val parser = NmeaParser()
    private val canonicalGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

    @Test
    fun checksumRequirementLatchesUntilSessionReset() {
        val unchecked = canonicalGga.substringBefore('*')
        assertTrue(parser.parse(unchecked) is NmeaParseResult.Parsed)
        assertTrue(parser.parse(canonicalGga) is NmeaParseResult.Parsed)
        val failed = parser.parse(unchecked) as NmeaParseResult.Failed
        assertEquals("missing checksum after checksummed sentences", failed.reason)
        assertEquals(unchecked, failed.raw)
        parser.resetSession()
        assertTrue(parser.parse(unchecked) is NmeaParseResult.Parsed)
        assertTrue(parser.parse(canonicalGga) is NmeaParseResult.Parsed)
        assertTrue(parser.parse(unchecked) is NmeaParseResult.Failed)
    }

    @Test
    fun invalidChecksumDoesNotLatchRequirement() {
        assertTrue(parser.parse(canonicalGga.dropLast(2) + "48") is NmeaParseResult.Failed)
        assertTrue(parser.parse(canonicalGga.substringBefore('*')) is NmeaParseResult.Parsed)
    }

    @Test
    fun validatedUnsupportedSentenceAlsoLatchesChecksumRequirement() {
        assertTrue(parser.parse(checked("GPZDA,120000,31,10,2026,00,00")) is NmeaParseResult.Unsupported)
        assertTrue(parser.parse(canonicalGga.substringBefore('*')) is NmeaParseResult.Failed)
    }

    @Test
    fun checksumMismatchUsesTwoDigitUppercaseHex() {
        // Known checksum 6A exercises uppercase A; 01 exercises zero padding.
        val raw = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*01"
        val result = parser.parse(raw) as NmeaParseResult.Failed
        assertEquals("checksum mismatch: sentence declares 01, computed 6A", result.reason)
        assertEquals(raw, result.raw)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "4e3", "4.0e3", "+4000.000", "-4000.000", "4000.000E0", "0x10", "0x1.0p1",
        "NaN", "Infinity", "40 00.000", "4000.", ".5", "00000.0",
    ])
    fun coordinateSyntaxRejectsNonDecimalAndOverwideFields(value: String) {
        val gga = parsed("GPGGA,120000,$value,N,$value,E,1,08,0.9,545.4,M,46.9,M,,") as GgaSentence
        assertNull(gga.latitude, "latitude spelling: $value")
        // A five-digit longitude is legal; test its width separately below.
        if (value != "00000.0") assertNull(gga.longitude, "longitude spelling: $value")
    }

    @Test
    fun coordinateWidthsDifferAndDroppedLeadingZeroRemainsAccepted() {
        val gga = parsed("GPGGA,120000,807.038,N,000000.0,E,1,08,0.9,545.4,M,46.9,M,,") as GgaSentence
        assertEquals(8.0 + 7.038 / 60.0, gga.latitude!!, 1e-9)
        assertNull(gga.longitude)
        val integer = parsed("GPGGA,120000,800,N,10500,W,1,08,0.9,545.4,M,46.9,M,,") as GgaSentence
        assertEquals(8.0, integer.latitude)
        assertEquals(-105.0, integer.longitude)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "1e99", "1.0E99", "4e3", "4.0e3", "4000.000E0", "+1.0", "0x10", "0x1.0p1",
        "NaN", "Infinity", "-Infinity", "1 0", "1.", ".5", "--1",
    ])
    fun numericFieldsRejectNonDecimalSpellings(value: String) {
        val gga = parsed("GPGGA,120000,4000,N,10500,W,1,08,$value,$value,M,$value,M,,") as GgaSentence
        assertNull(gga.hdop)
        assertNull(gga.altitudeMslMeters)
        assertNull(gga.geoidSeparationMeters)
        val rmc = parsed("GPRMC,120000,A,4000,N,10500,W,$value,$value,311026,,") as RmcSentence
        assertNull(rmc.speedKnots)
        assertNull(rmc.courseDeg)
        val vtg = parsed("GPVTG,$value,T,,M,$value,N,$value,K") as VtgSentence
        assertNull(vtg.courseTrueDeg)
        assertNull(vtg.speedKnots)
        assertNull(vtg.speedKmh)
    }

    @Test
    fun finiteDecimalFieldsAllowNegativesAndSurroundingWhitespace() {
        val gga = parsed("GPGGA,120000, 4000.0 ,N, 10500 ,W,1,08, 0.9 , -1000.0 ,M, -46.9 ,M,,") as GgaSentence
        assertEquals(40.0, gga.latitude)
        assertEquals(-105.0, gga.longitude)
        assertEquals(0.9, gga.hdop)
        assertEquals(-1000.0, gga.altitudeMslMeters)
        assertEquals(-46.9, gga.geoidSeparationMeters)
    }

    @Test
    fun overflowingDecimalFieldDecodesToNull() {
        val huge = "9".repeat(400)
        val gga = parsed("GPGGA,120000,4000,N,10500,W,1,08,$huge,$huge,M,$huge,M,,") as GgaSentence
        assertNull(gga.hdop)
        assertNull(gga.altitudeMslMeters)
        assertNull(gga.geoidSeparationMeters)
    }

    @ParameterizedTest
    @ValueSource(strings = ["M", "m", ""])
    fun metresOrEmptyUnitsKeepAltitudeAndGeoid(unit: String) {
        val gga = parsed("GPGGA,120000,4000,N,10500,W,1,08,0.9,545.4,$unit,46.9,$unit,,") as GgaSentence
        assertEquals(545.4, gga.altitudeMslMeters)
        assertEquals(46.9, gga.geoidSeparationMeters)
    }

    @Test
    fun altitudeAndGeoidUnitsAreCheckedIndependently() {
        val feetAltitude = parsed("GPGGA,120000,4000,N,10500,W,1,08,0.9,545.4,F,46.9,M,,") as GgaSentence
        assertNull(feetAltitude.altitudeMslMeters)
        assertEquals(46.9, feetAltitude.geoidSeparationMeters)
        val feetGeoid = parsed("GPGGA,120000,4000,N,10500,W,1,08,0.9,545.4,M,46.9,F,,") as GgaSentence
        assertEquals(545.4, feetGeoid.altitudeMslMeters)
        assertNull(feetGeoid.geoidSeparationMeters)
    }

    @Test
    fun rmcAndGllModeFieldsDecodeAtTheirSpecifiedIndices() {
        val rmc = parsed("GPRMC,120000,A,4000,N,10500,W,0.0,0.0,311026,,,D") as RmcSentence
        val gll = parsed("GPGLL,4000,N,10500,W,120000,A,E") as GllSentence
        assertEquals('D', rmc.mode)
        assertEquals('E', gll.mode)
        assertNull((parsed("GPRMC,120000,A,4000,N,10500,W,0,0,311026,,") as RmcSentence).mode)
        assertNull((parsed("GPGLL,4000,N,10500,W,120000,A,") as GllSentence).mode)
    }

    private fun parsed(body: String): NmeaSentence {
        val result = parser.parse(checked(body))
        assertTrue(result is NmeaParseResult.Parsed, "Fixture must parse: $result")
        return (result as NmeaParseResult.Parsed).sentence
    }

    private fun checked(body: String): String {
        val checksum = body.fold(0) { sum, char -> sum xor char.code }
        return "\$$body*${checksum.toString(16).uppercase().padStart(2, '0')}"
    }
}

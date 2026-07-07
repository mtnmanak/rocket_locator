package io.github.mtnmanak.rocketlocator26.core.nmea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Tests for [NmeaParser].
 *
 * Every fixture checksum was computed as the XOR of all characters strictly
 * between '$' and '*', expressed as two uppercase hex digits.
 */
class NmeaParserTest {

    private val parser = NmeaParser()

    /** Tolerance for decimal-degree comparisons. */
    private val eps = 1e-9

    // Canonical known-good fixtures (checksums verified).
    private val canonicalGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    private val canonicalRmc = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"

    // Expected decimal degrees for the canonical fixtures:
    // 4807.038  -> 48 deg + 7.038/60 min  = 48.1173
    // 01131.000 -> 11 deg + 31.0/60 min   = 11.516666666666667
    private val canonicalLat = 48.0 + 7.038 / 60.0
    private val canonicalLon = 11.0 + 31.0 / 60.0

    private fun parsed(line: String): NmeaSentence {
        val result = parser.parse(line)
        assertTrue(result is NmeaParseResult.Parsed, "expected Parsed but got: $result")
        return (result as NmeaParseResult.Parsed).sentence
    }

    private fun failed(line: String): NmeaParseResult.Failed {
        val result = parser.parse(line)
        assertTrue(result is NmeaParseResult.Failed, "expected Failed but got: $result")
        return result as NmeaParseResult.Failed
    }

    private fun unsupported(line: String): NmeaParseResult.Unsupported {
        val result = parser.parse(line)
        assertTrue(result is NmeaParseResult.Unsupported, "expected Unsupported but got: $result")
        return result as NmeaParseResult.Unsupported
    }

    @Nested
    inner class GgaDecoding {

        @Test
        fun `canonical GGA fixture decodes fully`() {
            val s = parsed(canonicalGga) as GgaSentence
            assertEquals("GP", s.talker)
            assertEquals("123519", s.timeUtc)
            assertEquals(canonicalLat, s.latitude!!, eps)
            assertEquals(canonicalLon, s.longitude!!, eps)
            assertEquals(1, s.fixQuality)
            assertEquals(8, s.satellitesInUse)
            assertEquals(0.9, s.hdop!!, eps)
            assertEquals(545.4, s.altitudeMslMeters!!, eps)
            assertEquals(46.9, s.geoidSeparationMeters!!, eps)
        }

        @Test
        fun `canonical GGA preserves raw line`() {
            val result = parser.parse(canonicalGga) as NmeaParseResult.Parsed
            assertEquals(canonicalGga, result.raw)
        }

        @Test
        fun `southern and western hemispheres are negative`() {
            // 3345.678 S  -> -(33 + 45.678/60) = -33.7613
            // 15112.345 W -> -(151 + 12.345/60) = -151.20575
            val s = parsed(
                "\$GPGGA,123519,3345.678,S,15112.345,W,1,08,0.9,10.0,M,,M,,*62",
            ) as GgaSentence
            assertEquals(-(33.0 + 45.678 / 60.0), s.latitude!!, eps)
            assertEquals(-33.7613, s.latitude!!, eps)
            assertEquals(-(151.0 + 12.345 / 60.0), s.longitude!!, eps)
            assertEquals(-151.20575, s.longitude!!, eps)
        }

        @Test
        fun `empty fields decode to nulls and fix quality zero`() {
            val s = parsed("\$GPGGA,,,,,,0,,,,,,,,*66") as GgaSentence
            assertNull(s.timeUtc)
            assertNull(s.latitude)
            assertNull(s.longitude)
            assertEquals(0, s.fixQuality)
            assertNull(s.satellitesInUse)
            assertNull(s.hdop)
            assertNull(s.altitudeMslMeters)
            assertNull(s.geoidSeparationMeters)
        }

        @Test
        fun `malformed fix quality decodes to zero not Failed`() {
            val s = parsed(
                "\$GPGGA,123519,4807.038,N,01131.000,E,X,08,0.9,545.4,M,46.9,M,,*2E",
            ) as GgaSentence
            assertEquals(0, s.fixQuality)
            // The rest of the sentence still decodes.
            assertEquals(canonicalLat, s.latitude!!, eps)
        }

        @Test
        fun `empty fix quality decodes to zero`() {
            val s = parsed(
                "\$GPGGA,123519,4807.038,N,01131.000,E,,08,0.9,545.4,M,46.9,M,,*76",
            ) as GgaSentence
            assertEquals(0, s.fixQuality)
        }

        @Test
        fun `malformed latitude digits decode to null latitude only`() {
            val s = parsed(
                "\$GPGGA,123519,ABCD.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,",
            ) as GgaSentence
            assertNull(s.latitude)
            assertEquals(canonicalLon, s.longitude!!, eps)
            assertEquals(1, s.fixQuality)
        }

        @Test
        fun `latitude with missing hemisphere is null`() {
            val s = parsed(
                "\$GPGGA,123519,4807.038,,01131.000,E,1,08,0.9,545.4,M,46.9,M,,",
            ) as GgaSentence
            assertNull(s.latitude)
            assertEquals(canonicalLon, s.longitude!!, eps)
        }

        @Test
        fun `latitude with unknown hemisphere letter is null not wrong-signed`() {
            val s = parsed(
                "\$GPGGA,123519,4807.038,Q,01131.000,E,1,08,0.9,545.4,M,46.9,M,,",
            ) as GgaSentence
            assertNull(s.latitude)
        }

        @Test
        fun `coordinate with minutes of 60 or more is null`() {
            // 4877.0 would be 48 degrees 77 minutes: impossible.
            val s = parsed(
                "\$GPGGA,123519,4877.000,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,",
            ) as GgaSentence
            assertNull(s.latitude)
        }

        @Test
        fun `latitude beyond 90 degrees is null`() {
            val s = parsed(
                "\$GPGGA,123519,9130.000,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,",
            ) as GgaSentence
            assertNull(s.latitude)
        }

        @Test
        fun `NaN in coordinate and hdop fields decodes to null`() {
            val s = parsed(
                "\$GPGGA,123519,NaN,N,01131.000,E,1,08,NaN,545.4,M,46.9,M,,",
            ) as GgaSentence
            assertNull(s.latitude)
            assertNull(s.hdop)
        }

        @Test
        fun `extra fields beyond the expected count are ignored`() {
            val s = parsed(
                "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,,extra1,extra2",
            ) as GgaSentence
            assertEquals(canonicalLat, s.latitude!!, eps)
            assertEquals(545.4, s.altitudeMslMeters!!, eps)
        }

        @Test
        fun `short GGA decodes available fields with trailing nulls`() {
            val s = parsed("\$GPGGA,123519,4807.038,N") as GgaSentence
            assertEquals("123519", s.timeUtc)
            assertEquals(canonicalLat, s.latitude!!, eps)
            assertNull(s.longitude)
            assertEquals(0, s.fixQuality)
            assertNull(s.altitudeMslMeters)
        }
    }

    @Nested
    inner class RmcDecoding {

        @Test
        fun `canonical RMC fixture decodes fully`() {
            val s = parsed(canonicalRmc) as RmcSentence
            assertEquals("GP", s.talker)
            assertEquals("123519", s.timeUtc)
            assertTrue(s.isValid)
            assertEquals(canonicalLat, s.latitude!!, eps)
            assertEquals(canonicalLon, s.longitude!!, eps)
            assertEquals(22.4, s.speedKnots!!, eps)
            assertEquals(84.4, s.courseDeg!!, eps)
            assertEquals("230394", s.date)
        }

        @Test
        fun `void RMC has isValid false and null position`() {
            val s = parsed("\$GPRMC,123519,V,,,,,,,230394,,*33") as RmcSentence
            assertFalse(s.isValid)
            assertNull(s.latitude)
            assertNull(s.longitude)
            assertNull(s.speedKnots)
            assertNull(s.courseDeg)
            assertEquals("230394", s.date)
        }

        @Test
        fun `RMC with missing status field is not valid`() {
            val s = parsed("\$GPRMC,123519,,4807.038,N,01131.000,E,,,,,") as RmcSentence
            assertFalse(s.isValid)
            assertEquals(canonicalLat, s.latitude!!, eps)
        }
    }

    @Nested
    inner class OtherSentenceTypes {

        @Test
        fun `GLL decodes position time and validity`() {
            // 4916.45 N   -> 49 + 16.45/60  = 49.27416666...
            // 12311.12 W  -> -(123 + 11.12/60) = -123.18533333...
            val s = parsed("\$GPGLL,4916.45,N,12311.12,W,225444,A*31") as GllSentence
            assertEquals("GP", s.talker)
            assertEquals(49.0 + 16.45 / 60.0, s.latitude!!, eps)
            assertEquals(-(123.0 + 11.12 / 60.0), s.longitude!!, eps)
            assertEquals("225444", s.timeUtc)
            assertTrue(s.isValid)
        }

        @Test
        fun `VTG decodes course and both speeds`() {
            val s = parsed("\$GPVTG,054.7,T,034.4,M,005.5,N,010.2,K*48") as VtgSentence
            assertEquals("GP", s.talker)
            assertEquals(54.7, s.courseTrueDeg!!, eps)
            assertEquals(5.5, s.speedKnots!!, eps)
            assertEquals(10.2, s.speedKmh!!, eps)
        }

        @Test
        fun `GSA decodes fix type and DOPs`() {
            val s = parsed("\$GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1*39") as GsaSentence
            assertEquals("GP", s.talker)
            assertEquals(3, s.fixType)
            assertEquals(2.5, s.pdop!!, eps)
            assertEquals(1.3, s.hdop!!, eps)
            assertEquals(2.1, s.vdop!!, eps)
        }

        @Test
        fun `truncated GSA yields null DOPs`() {
            val s = parsed("\$GPGSA,A,3,04,05") as GsaSentence
            assertEquals(3, s.fixType)
            assertNull(s.pdop)
            assertNull(s.hdop)
            assertNull(s.vdop)
        }

        @Test
        fun `GSV decodes message counts and satellites in view`() {
            val s = parsed(
                "\$GPGSV,2,1,08,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*75",
            ) as GsvSentence
            assertEquals("GP", s.talker)
            assertEquals(2, s.totalMessages)
            assertEquals(1, s.messageNumber)
            assertEquals(8, s.satellitesInView)
        }
    }

    @Nested
    inner class Talkers {

        @Test
        fun `GN talker GGA is accepted`() {
            val s = parsed(
                "\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*59",
            ) as GgaSentence
            assertEquals("GN", s.talker)
            assertEquals(canonicalLat, s.latitude!!, eps)
        }

        @Test
        fun `GN talker RMC is accepted`() {
            val s = parsed(
                "\$GNRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*74",
            ) as RmcSentence
            assertEquals("GN", s.talker)
            assertTrue(s.isValid)
        }

        @Test
        fun `GL talker GLL is accepted`() {
            val s = parsed("\$GLGLL,4916.45,N,12311.12,W,225444,A*2D") as GllSentence
            assertEquals("GL", s.talker)
        }

        @Test
        fun `GA talker VTG is accepted`() {
            val s = parsed("\$GAVTG,054.7,T,034.4,M,005.5,N,010.2,K*59") as VtgSentence
            assertEquals("GA", s.talker)
        }

        @Test
        fun `GB talker GSV is accepted`() {
            val s = parsed(
                "\$GBGSV,3,2,11,09,12,034,,10,45,176,42,20,05,290,,23,70,010,38*61",
            ) as GsvSentence
            assertEquals("GB", s.talker)
            assertEquals(11, s.satellitesInView)
        }

        @Test
        fun `future unknown two-letter talker is accepted`() {
            val s = parsed("\$QZGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,") as GgaSentence
            assertEquals("QZ", s.talker)
        }
    }

    @Nested
    inner class Checksums {

        @Test
        fun `checksum mismatch is Failed`() {
            val corrupted = canonicalGga.dropLast(2) + "48"
            val f = failed(corrupted)
            assertTrue(f.reason.contains("checksum", ignoreCase = true), "reason was: ${f.reason}")
            assertEquals(corrupted, f.raw)
        }

        @Test
        fun `missing checksum is accepted`() {
            val noChecksum = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
            val s = parsed(noChecksum) as GgaSentence
            assertEquals(canonicalLat, s.latitude!!, eps)
            assertEquals(1, s.fixQuality)
        }

        @Test
        fun `lowercase checksum hex is accepted`() {
            val lower = canonicalRmc.dropLast(2) + "6a"
            val s = parsed(lower) as RmcSentence
            assertTrue(s.isValid)
        }

        @Test
        fun `single hex digit after star is Failed`() {
            failed("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*4")
        }

        @Test
        fun `non-hex checksum digits are Failed`() {
            failed("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*ZZ")
        }

        @Test
        fun `star with nothing after it is Failed`() {
            failed("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*")
        }
    }

    @Nested
    inner class Framing {

        @Test
        fun `trailing CRLF is trimmed`() {
            val s = parsed(canonicalGga + "\r\n") as GgaSentence
            assertEquals(canonicalLat, s.latitude!!, eps)
        }

        @Test
        fun `garbage before the dollar sign is skipped`() {
            val line = "ÿnoise 12.3,N*00" + canonicalGga
            val result = parser.parse(line)
            assertTrue(result is NmeaParseResult.Parsed, "expected Parsed but got: $result")
            val s = (result as NmeaParseResult.Parsed).sentence as GgaSentence
            assertEquals(canonicalLat, s.latitude!!, eps)
            assertEquals(line, result.raw)
        }

        @Test
        fun `pure garbage without a dollar is Failed`() {
            failed("askjdh aksjh 1234!!")
        }

        @Test
        fun `empty string is Failed`() {
            val f = failed("")
            assertEquals("", f.raw)
        }

        @Test
        fun `whitespace-only line is Failed`() {
            failed(" \r\n\t ")
        }

        @Test
        fun `lone dollar sign is Failed`() {
            failed("\$")
        }

        @Test
        fun `truncated sentence with fewer than three fields is Failed`() {
            failed("\$GPGGA,123519")
            failed("\$GPGGA")
        }

        @Test
        fun `malformed address is Failed`() {
            failed("\$GPGG,1,2,3")     // 4-char address
            failed("\$GPGGA1,1,2,3")   // 6-char address
            failed("\$G2GGA,1,2,3")    // digit in talker
            failed("\$,1,2,3")          // empty address
        }

        @Test
        fun `lowercase sentence without checksum is accepted`() {
            val s = parsed("\$gpgga,123519,4807.038,n,01131.000,e,1,08,0.9,545.4,m,46.9,m,,") as GgaSentence
            assertEquals("GP", s.talker)
            assertEquals(canonicalLat, s.latitude!!, eps)
            assertEquals(canonicalLon, s.longitude!!, eps)
        }
    }

    @Nested
    inner class UnsupportedSentences {

        @Test
        fun `GST is Unsupported with its type`() {
            val u = unsupported("\$GPGST,172814.0,0.006,0.023,0.020,273.6,0.023,0.020,0.031*6A")
            assertEquals("GST", u.type)
        }

        @Test
        fun `proprietary PGRMZ is Unsupported`() {
            val line = "\$PGRMZ,93,f,3*21"
            val u = unsupported(line)
            assertEquals("PGRMZ", u.type)
            assertEquals(line, u.raw)
        }

        @Test
        fun `proprietary sentence with bad checksum is still Failed`() {
            failed("\$PGRMZ,93,f,3*22")
        }
    }

    @Nested
    inner class Robustness {

        @Test
        fun `megabyte-long line does not throw`() {
            val huge = "\$GPGGA," + "9".repeat(1_000_000)
            val result = parser.parse(huge)
            // Any NmeaParseResult is acceptable; not throwing is the contract.
            assertTrue(
                result is NmeaParseResult.Parsed ||
                    result is NmeaParseResult.Unsupported ||
                    result is NmeaParseResult.Failed,
            )
        }

        @Test
        fun `unicode field content does not throw`() {
            val result = parser.parse("\$GPGGA,ünïçødé,4807.038,N,🚀,E,1,,,,,,,,")
            assertTrue(result is NmeaParseResult.Parsed, "expected Parsed but got: $result")
            val s = (result as NmeaParseResult.Parsed).sentence as GgaSentence
            assertNull(s.longitude) // rocket emoji is not a coordinate
        }

        @Test
        fun `fuzz - 1000 random byte strings never throw`() {
            val random = Random(20260707)
            val structural = charArrayOf('$', '*', ',', '.', 'N', 'S', 'E', 'W', 'G', 'P', 'A')
            repeat(1000) { iteration ->
                val length = random.nextInt(0, 300)
                val chars = CharArray(length) {
                    // Bias toward NMEA structural characters so the fuzzer
                    // reaches deep parser paths, not just the framing checks.
                    if (random.nextInt(4) == 0) {
                        structural[random.nextInt(structural.size)]
                    } else {
                        Char(random.nextInt(0, 256))
                    }
                }
                val line = String(chars)
                try {
                    parser.parse(line)
                } catch (t: Throwable) {
                    throw AssertionError(
                        "parse threw on fuzz iteration $iteration for input: " +
                            line.map { it.code }.toString(),
                        t,
                    )
                }
            }
        }
    }
}

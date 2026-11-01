package io.github.mtnmanak.rocketlocator26.core.nmea

/**
 * Parser for NMEA 0183 sentences as emitted by rocket-tracker GPS receivers
 * (Eggfinder, Missile Works T3/RTx, and similar).
 *
 * Design goals, in priority order:
 *
 * 1. **Never throw.** Receivers in the field deliver truncated, corrupted, and
 *    interleaved garbage; any input whatsoever yields an [NmeaParseResult].
 * 2. **Never report a wrong position silently.** Coordinate fields that cannot
 *    be trusted (malformed digits, missing or unknown hemisphere, out-of-range
 *    values) decode to `null` rather than a plausible-but-wrong number.
 * 3. **Be tolerant of real-world quirks:** leading garbage before `'$'`
 *    (partial lines mid-stream), missing checksums until this session has
 *    validated a checksum (some devices omit them),
 *    lowercase checksum hex, extra trailing fields from newer NMEA revisions,
 *    and short sentences from older firmware.
 *
 * Supported sentence types: GGA, RMC, GLL, VTG, GSA, GSV, from any two-letter
 * talker (GP, GN, GL, GA, GB, and future ones). Proprietary `$P...` sentences
 * and other well-formed standard types are reported as
 * [NmeaParseResult.Unsupported].
 *
 * Session state records whether this receiver sends checksums. Feed from a
 * single consumer; this parser is not thread-safe. Call [resetSession] on connect.
 */
class NmeaParser {
    private var sawChecksum = false

    /** Starts a receiver session, allowing checksum-less legacy devices again. */
    fun resetSession() {
        sawChecksum = false
    }

    /**
     * Parses a single line of receiver output.
     *
     * The line is trimmed of surrounding whitespace/CR/LF. If `'$'` appears
     * after leading garbage, parsing starts from the `'$'`. If a `'*'` is
     * present, the two hex digits following it are validated as the XOR of all
     * characters strictly between `'$'` and `'*'`; if no `'*'` is present the
     * sentence is accepted only until a checksum has validated in this session.
     *
     * This method never throws, regardless of input.
     *
     * @param line one raw line from the receiver (any content is safe).
     * @return [NmeaParseResult.Parsed] for a decoded sentence,
     *   [NmeaParseResult.Unsupported] for well-formed sentences of a type this
     *   parser does not decode, or [NmeaParseResult.Failed] otherwise. The
     *   original [line] is carried through unmodified as `raw`.
     */
    fun parse(line: String): NmeaParseResult =
        try {
            parseInternal(line)
        } catch (t: Throwable) {
            // Belt-and-braces: parseInternal is written not to throw, but the
            // never-throw contract is absolute (pre-1.4.1 NPE crash bug).
            NmeaParseResult.Failed("internal parser error: ${t::class.simpleName}: ${t.message}", line)
        }

    private fun parseInternal(line: String): NmeaParseResult {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            return NmeaParseResult.Failed("empty line", line)
        }

        val dollar = trimmed.indexOf('$')
        if (dollar < 0) {
            return NmeaParseResult.Failed("no '$' start delimiter", line)
        }
        // Everything between '$' and '*' (or end of line) is the sentence body.
        val afterDollar = trimmed.substring(dollar + 1)

        val star = afterDollar.indexOf('*')
        val body: String
        if (star >= 0) {
            body = afterDollar.substring(0, star)
            val checksumField = afterDollar.substring(star + 1)
            if (checksumField.length != 2) {
                return NmeaParseResult.Failed(
                    "malformed checksum field '$checksumField' (expected two hex digits)",
                    line,
                )
            }
            val expected =
                checksumField.toIntOrNull(radix = 16)
                    ?: return NmeaParseResult.Failed(
                        "malformed checksum field '$checksumField' (not hex)",
                        line,
                    )
            val computed = body.fold(0) { acc, c -> acc xor c.code }
            if (computed != expected) {
                return NmeaParseResult.Failed(
                    "checksum mismatch: sentence declares ${hex(expected)}, computed ${hex(computed)}",
                    line,
                )
            }
            sawChecksum = true
        } else {
            if (sawChecksum) {
                return NmeaParseResult.Failed("missing checksum after checksummed sentences", line)
            }
            // Legacy checksum-less devices remain supported in a fresh session.
            body = afterDollar
        }

        val fields = body.split(',')
        val address = fields[0].trim().uppercase()
        if (address.isEmpty()) {
            return NmeaParseResult.Failed("empty address field", line)
        }
        if (address.startsWith("P")) {
            // Proprietary sentence, e.g. $PGRMZ. Never decoded.
            return NmeaParseResult.Unsupported(address, line)
        }
        if (address.length != 5 || !address.all { it in 'A'..'Z' }) {
            return NmeaParseResult.Failed("malformed address field '${fields[0]}'", line)
        }
        val talker = address.substring(0, 2)
        val type = address.substring(2)

        if (type !in SUPPORTED_TYPES) {
            return NmeaParseResult.Unsupported(type, line)
        }
        if (fields.size < MIN_FIELDS) {
            return NmeaParseResult.Failed(
                "too few fields for $type (${fields.size}, need at least $MIN_FIELDS)",
                line,
            )
        }

        val sentence =
            when (type) {
                "GGA" -> decodeGga(talker, fields)
                "RMC" -> decodeRmc(talker, fields)
                "GLL" -> decodeGll(talker, fields)
                "VTG" -> decodeVtg(talker, fields)
                "GSA" -> decodeGsa(talker, fields)
                "GSV" -> decodeGsv(talker, fields)
                else -> return NmeaParseResult.Unsupported(type, line) // unreachable
            }
        return NmeaParseResult.Parsed(sentence, line)
    }

    // --- Sentence decoders ------------------------------------------------
    // Field index 0 is always the address (e.g. "GPGGA"); data fields start at 1.
    // Missing trailing fields decode to null; extra trailing fields are ignored.

    private fun decodeGga(
        talker: String,
        f: List<String>,
    ): GgaSentence =
        GgaSentence(
            talker = talker,
            timeUtc = field(f, 1),
            latitude = coordinate(f, valueIndex = 2, hemisphereIndex = 3, isLatitude = true),
            longitude = coordinate(f, valueIndex = 4, hemisphereIndex = 5, isLatitude = false),
            // Contract: malformed or empty fix quality means "no fix", not Failed.
            fixQuality = intField(f, 6) ?: 0,
            satellitesInUse = intField(f, 7),
            hdop = doubleField(f, 8),
            altitudeMslMeters = metersField(f, 9, 10),
            geoidSeparationMeters = metersField(f, 11, 12),
        )

    private fun decodeRmc(
        talker: String,
        f: List<String>,
    ): RmcSentence =
        RmcSentence(
            talker = talker,
            timeUtc = field(f, 1),
            isValid = field(f, 2)?.equals("A", ignoreCase = true) == true,
            latitude = coordinate(f, valueIndex = 3, hemisphereIndex = 4, isLatitude = true),
            longitude = coordinate(f, valueIndex = 5, hemisphereIndex = 6, isLatitude = false),
            speedKnots = doubleField(f, 7),
            courseDeg = doubleField(f, 8),
            date = field(f, 9),
            mode = modeField(f, 12),
        )

    private fun decodeGll(
        talker: String,
        f: List<String>,
    ): GllSentence =
        GllSentence(
            talker = talker,
            latitude = coordinate(f, valueIndex = 1, hemisphereIndex = 2, isLatitude = true),
            longitude = coordinate(f, valueIndex = 3, hemisphereIndex = 4, isLatitude = false),
            timeUtc = field(f, 5),
            isValid = field(f, 6)?.equals("A", ignoreCase = true) == true,
            mode = modeField(f, 7),
        )

    private fun decodeVtg(
        talker: String,
        f: List<String>,
    ): VtgSentence =
        VtgSentence(
            talker = talker,
            courseTrueDeg = doubleField(f, 1),
            speedKnots = doubleField(f, 5),
            speedKmh = doubleField(f, 7),
        )

    private fun decodeGsa(
        talker: String,
        f: List<String>,
    ): GsaSentence =
        GsaSentence(
            talker = talker,
            // 1 means "no fix" — the safe default when the field is malformed.
            fixType = intField(f, 2) ?: 1,
            pdop = doubleField(f, 15),
            hdop = doubleField(f, 16),
            vdop = doubleField(f, 17),
        )

    private fun decodeGsv(
        talker: String,
        f: List<String>,
    ): GsvSentence =
        GsvSentence(
            talker = talker,
            totalMessages = intField(f, 1),
            messageNumber = intField(f, 2),
            satellitesInView = intField(f, 3),
        )

    // --- Field helpers ----------------------------------------------------

    /** Returns data field [index] trimmed, or null when absent or empty. */
    private fun field(
        fields: List<String>,
        index: Int,
    ): String? = fields.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }

    /** Field as Int, or null when absent, empty, or malformed. */
    private fun intField(
        fields: List<String>,
        index: Int,
    ): Int? = field(fields, index)?.toIntOrNull()

    /** Field as finite Double, or null when absent, empty, or malformed. */
    private fun doubleField(
        fields: List<String>,
        index: Int,
    ): Double? =
        field(fields, index)
            ?.takeIf { DECIMAL.matches(it) }
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }

    /** Empty units support older firmware; other explicit units are not metres. */
    private fun metersField(
        fields: List<String>,
        valueIndex: Int,
        unitIndex: Int,
    ): Double? {
        val unit = field(fields, unitIndex)
        return if (unit == null || unit.equals("M", ignoreCase = true)) {
            doubleField(fields, valueIndex)
        } else {
            null
        }
    }

    /** Malformed nonempty modes must not masquerade as an absent legacy field. */
    private fun modeField(
        fields: List<String>,
        index: Int,
    ): Char? = field(fields, index)?.let { it.singleOrNull()?.uppercaseChar() ?: '?' }

    private fun hex(value: Int): String = value.toString(16).padStart(2, '0').uppercase()

    /**
     * Decodes an NMEA coordinate pair (value field + hemisphere field) to
     * signed decimal degrees.
     *
     * The value is `ddmm.mmmm` for latitude / `dddmm.mmmm` for longitude:
     * degrees are `value / 100` truncated, minutes are the remainder.
     * South and west are negative.
     *
     * Returns null — never a guess — when the value or hemisphere is missing,
     * malformed, or out of range (minutes >= 60, |lat| > 90, |lon| > 180).
     */
    private fun coordinate(
        fields: List<String>,
        valueIndex: Int,
        hemisphereIndex: Int,
        isLatitude: Boolean,
    ): Double? {
        val syntax = if (isLatitude) LATITUDE else LONGITUDE
        val value =
            field(fields, valueIndex)
                ?.takeIf { syntax.matches(it) }
                ?.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value < 0.0) return null
        val degrees = (value / 100.0).toInt()
        val minutes = value - degrees * 100.0
        if (minutes >= 60.0) return null
        val decimal = degrees + minutes / 60.0
        val limit = if (isLatitude) 90.0 else 180.0
        if (decimal > limit) return null
        val hemisphere = field(fields, hemisphereIndex)?.uppercase() ?: return null
        val positive = if (isLatitude) "N" else "E"
        val negative = if (isLatitude) "S" else "W"
        return when (hemisphere) {
            positive -> decimal
            negative -> -decimal
            else -> null
        }
    }

    private companion object {
        val DECIMAL = Regex("-?\\d+(\\.\\d+)?")
        val LATITUDE = Regex("\\d{1,4}(\\.\\d+)?")
        val LONGITUDE = Regex("\\d{1,5}(\\.\\d+)?")

        /** Sentence types this parser decodes into model objects. */
        val SUPPORTED_TYPES = setOf("GGA", "RMC", "GLL", "VTG", "GSA", "GSV")

        /**
         * Minimum comma-separated field count (including the address) for a
         * supported sentence to be meaningful.
         */
        const val MIN_FIELDS = 3
    }
}

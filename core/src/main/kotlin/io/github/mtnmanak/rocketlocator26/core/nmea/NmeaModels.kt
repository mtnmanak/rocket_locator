package io.github.mtnmanak.rocketlocator26.core.nmea

/**
 * A successfully parsed NMEA 0183 sentence.
 *
 * [talker] is the two-letter talker ID: GP (GPS), GN (multi-GNSS), GL (GLONASS),
 * GA (Galileo), GB (BeiDou). Trackers in the field emit any of these depending on
 * their GPS chipset generation.
 */
sealed interface NmeaSentence {
    val talker: String
}

/**
 * GGA — Global Positioning System Fix Data. The primary position sentence;
 * every supported tracker (Eggfinder, Missile Works T3/RTx) emits it.
 *
 * Coordinates are decimal degrees: south and west are negative.
 * Fields absent from the sentence (empty between commas) are null.
 */
data class GgaSentence(
    override val talker: String,
    /** UTC time as transmitted, "hhmmss" or "hhmmss.sss". */
    val timeUtc: String?,
    val latitude: Double?,
    val longitude: Double?,
    /** 0 = no fix, 1 = GPS fix, 2 = DGPS, 4/5 = RTK, 6 = dead reckoning. */
    val fixQuality: Int,
    val satellitesInUse: Int?,
    val hdop: Double?,
    val altitudeMslMeters: Double?,
    val geoidSeparationMeters: Double?,
) : NmeaSentence

/** RMC — Recommended Minimum. Position, speed, course, and date. */
data class RmcSentence(
    override val talker: String,
    val timeUtc: String?,
    /** True when status field is "A" (active); false for "V" (void). */
    val isValid: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val speedKnots: Double?,
    val courseDeg: Double?,
    /** Date as transmitted, "ddmmyy". */
    val date: String?,
) : NmeaSentence

/** GLL — Geographic position, latitude/longitude. */
data class GllSentence(
    override val talker: String,
    val latitude: Double?,
    val longitude: Double?,
    val timeUtc: String?,
    val isValid: Boolean,
) : NmeaSentence

/** VTG — Course over ground and ground speed. */
data class VtgSentence(
    override val talker: String,
    val courseTrueDeg: Double?,
    val speedKnots: Double?,
    val speedKmh: Double?,
) : NmeaSentence

/** GSA — DOP and active satellites. */
data class GsaSentence(
    override val talker: String,
    /** 1 = no fix, 2 = 2D, 3 = 3D. */
    val fixType: Int,
    val pdop: Double?,
    val hdop: Double?,
    val vdop: Double?,
) : NmeaSentence

/** GSV — Satellites in view. Only the summary count is retained. */
data class GsvSentence(
    override val talker: String,
    val totalMessages: Int?,
    val messageNumber: Int?,
    val satellitesInView: Int?,
) : NmeaSentence

/**
 * Result of parsing one line of receiver output.
 *
 * Contract: parsing must NEVER throw, regardless of input. Receivers deliver
 * truncated, corrupted, and interleaved garbage in the field — the original
 * app crashed on this (pre-1.4.1 NPE bug) and we will not repeat that.
 */
sealed interface NmeaParseResult {
    data class Parsed(val sentence: NmeaSentence, val raw: String) : NmeaParseResult

    /** Well-formed NMEA of a sentence type we don't decode (e.g. GST, ZDA). */
    data class Unsupported(val type: String, val raw: String) : NmeaParseResult

    /** Not parseable: bad framing, failed checksum, malformed fields. */
    data class Failed(val reason: String, val raw: String) : NmeaParseResult
}

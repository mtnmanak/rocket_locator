package io.github.mtnmanak.rocketlocator26.transport

/**
 * Reassembles complete NMEA lines from arbitrarily fragmented byte chunks.
 *
 * Bluetooth links deliver data in whatever pieces the stack hands over: Classic
 * SPP reads return arbitrary chunk sizes, and BLE GATT notifications arrive as
 * ~20-byte fragments. This class accumulates those fragments and emits one
 * string per complete line, splitting on `'\n'` and stripping a trailing
 * `'\r'` if present. A partial line at the end of a chunk is retained and
 * completed by later [feed] calls.
 *
 * Bytes are decoded as ISO-8859-1: NMEA is plain ASCII, and this decoding maps
 * every byte value to a character, so [feed] never throws on arbitrary (even
 * binary) input. Empty lines are never emitted.
 *
 * Memory is bounded forever: if a pending partial line grows beyond
 * [maxLineLength] bytes without a terminator (binary noise on the link), the
 * accumulated bytes are discarded and everything up to and including the next
 * `'\n'` is skipped, after which assembly resumes normally with the next line.
 *
 * Not thread-safe; feed it from a single reader coroutine/thread.
 *
 * @param maxLineLength longest line, in bytes (terminator excluded), that will
 *   be buffered before the pending partial is declared garbage and dropped.
 *   Real NMEA sentences are at most 82 characters; the generous default also
 *   tolerates vendor extensions and debug output.
 */
class NmeaLineAssembler(private val maxLineLength: Int = 1024) {

    init {
        require(maxLineLength > 0) { "maxLineLength must be positive, was $maxLineLength" }
    }

    /** Fixed-size accumulation buffer for the current (partial) line. */
    private val pending = ByteArray(maxLineLength)

    /** Number of valid bytes currently held in [pending]. */
    private var pendingLength = 0

    /** True while skipping the remainder of an overlong line, up to its `'\n'`. */
    private var discardingOverlongLine = false

    /**
     * Feeds the first [count] bytes of [bytes] into the assembler.
     *
     * @param bytes chunk of raw link data; only indices `0 until count` are read.
     * @param count number of valid bytes in [bytes] (a read call's return value);
     *   defaults to the whole array.
     * @return every line completed by this chunk, in order, without terminators;
     *   empty if no `'\n'` was seen or only blank lines were completed.
     */
    fun feed(bytes: ByteArray, count: Int = bytes.size): List<String> {
        require(count in 0..bytes.size) {
            "count must be in 0..${bytes.size}, was $count"
        }
        val lines = mutableListOf<String>()
        for (i in 0 until count) {
            val byte = bytes[i]
            if (byte == LINE_FEED) {
                if (discardingOverlongLine) {
                    // End of the garbage run: resume normal assembly, emit nothing.
                    discardingOverlongLine = false
                } else {
                    takePendingLine()?.let(lines::add)
                }
            } else if (!discardingOverlongLine) {
                if (pendingLength == maxLineLength) {
                    // The partial has exceeded maxLineLength with no terminator in
                    // sight: drop it and skip ahead to the next line boundary.
                    pendingLength = 0
                    discardingOverlongLine = true
                } else {
                    pending[pendingLength++] = byte
                }
            }
        }
        return lines
    }

    /**
     * Consumes the pending buffer as one line: strips a trailing `'\r'`, resets
     * the buffer, and returns the decoded text, or null if the line was blank.
     */
    private fun takePendingLine(): String? {
        var length = pendingLength
        pendingLength = 0
        if (length > 0 && pending[length - 1] == CARRIAGE_RETURN) {
            length--
        }
        if (length == 0) return null
        return String(pending, 0, length, Charsets.ISO_8859_1)
    }

    private companion object {
        const val LINE_FEED = '\n'.code.toByte()
        const val CARRIAGE_RETURN = '\r'.code.toByte()
    }
}

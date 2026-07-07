package io.github.mtnmanak.rocketlocator26.transport

import kotlinx.coroutines.flow.Flow

/**
 * Lifecycle and data events emitted by an [NmeaSource].
 *
 * A well-behaved source emits [Connected] once the link is up, zero or more
 * [LineReceived] events while data flows, [SourceError] for recoverable or fatal
 * link problems, and [Disconnected] when the link goes down.
 */
sealed interface SourceEvent {
    /** The underlying link is established; [LineReceived] events may follow. */
    data object Connected : SourceEvent

    /** The underlying link has closed (end of data, remote drop, or shutdown). */
    data object Disconnected : SourceEvent

    /**
     * One raw line of receiver output, without line terminators.
     *
     * @property raw the line exactly as received, e.g. a full NMEA sentence.
     * @property timestampMs when the line was received, in milliseconds. For live
     *   links this is a wall-clock-derived reception time; for replay/simulator
     *   sources it is the (scaled) offset from the start of playback.
     */
    data class LineReceived(val raw: String, val timestampMs: Long) : SourceEvent

    /**
     * A link problem occurred.
     *
     * @property message human-readable description for logs/UI.
     * @property willRetry true if the source will attempt to recover on its own
     *   (e.g. reconnect with backoff); false if the failure is terminal.
     */
    data class SourceError(val message: String, val willRetry: Boolean) : SourceEvent
}

/** A stream of raw NMEA lines from some receiver link (Bluetooth SPP, BLE, simulator...). */
interface NmeaSource {
    /** Cold flow: collection starts the source, cancellation stops it. */
    fun events(): Flow<SourceEvent>

    /** Human-readable source name for the UI, e.g. "Simulator", "HC-06 (Eggfinder)". */
    val name: String
}

package io.github.mtnmanak.rocketlocator26.telemetry

import io.github.mtnmanak.rocketlocator26.core.flight.FlightTracker
import io.github.mtnmanak.rocketlocator26.core.flight.RocketState
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParser
import io.github.mtnmanak.rocketlocator26.transport.NmeaSource
import io.github.mtnmanak.rocketlocator26.transport.SourceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Link status of the active telemetry source. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Everything the app knows about the current telemetry session.
 *
 * @property connectionState link status of the active source.
 * @property sourceName human-readable name of the active (or last active)
 *   source, e.g. "Simulator" or "HC-06 (Eggfinder)"; null before the first
 *   [TelemetryRepository.connect].
 * @property rocket latest tracked rocket state (position, altitudes, fix
 *   info, path).
 * @property lastRawLine most recent raw NMEA line, for the live ticker.
 * @property recentLines ring buffer of raw lines and `[error]`-prefixed
 *   source errors for the console view: at most the last 100 entries,
 *   newest LAST.
 */
data class TelemetryUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val sourceName: String? = null,
    val rocket: RocketState = RocketState(),
    val lastRawLine: String? = null,
    val recentLines: List<String> = emptyList(),
)

/**
 * Single process-wide telemetry pipeline: consumes one [NmeaSource] at a
 * time, parses its NMEA lines, feeds a [FlightTracker], and publishes the
 * result as observable [uiState].
 *
 * Owned by the Application (not a ViewModel) so the pipeline survives
 * configuration changes and keeps running under the foreground service with
 * the screen off.
 *
 * ## Threading
 * [FlightTracker] is not thread-safe, so consumption is strictly
 * single-threaded: [scope] must be main-dispatched (`Dispatchers.Main.immediate`
 * is fine — parsing one NMEA line per second is trivial), and [connect] /
 * [disconnect] must be called from the main thread. A session counter guards
 * against events from a superseded source mutating state after a rapid
 * connect/disconnect/connect sequence.
 *
 * @param scope main-dispatched scope in which source flows are collected;
 *   outlives any single source (typically the application scope).
 */
class TelemetryRepository(private val scope: CoroutineScope) {

    private val parser = NmeaParser()
    private val tracker = FlightTracker()

    private val _uiState = MutableStateFlow(TelemetryUiState())

    /** Observable telemetry snapshot; always holds the latest state. */
    val uiState: StateFlow<TelemetryUiState> = _uiState.asStateFlow()

    /** Collection job for the active source, if any. */
    private var activeJob: Job? = null

    /**
     * Monotonic session token. Incremented on every [connect] and
     * [disconnect] so event handlers from a superseded source can detect
     * they are stale and drop their events.
     */
    private var session = 0

    /**
     * True between a retryable [SourceEvent.SourceError] and the next
     * [SourceEvent.Connected]: the source is backing off to reconnect on its
     * own, so the [SourceEvent.Disconnected] it emits in between must read
     * as CONNECTING, not DISCONNECTED. DISCONNECTED is reserved for terminal
     * states (user disconnect, fatal error, source completed) so observers —
     * including [TrackingService] — can treat it as "session over".
     */
    private var retryPending = false

    /**
     * Starts consuming [source], replacing any active source.
     *
     * Cancels the previous source's collection, resets the [FlightTracker]
     * and console (a new session starts from a clean slate), publishes
     * [ConnectionState.CONNECTING] plus the source's name, and collects
     * [NmeaSource.events] in [scope]. State transitions then follow the
     * source's events; if the flow completes normally (e.g. the simulator
     * finishes its flight), the state becomes
     * [ConnectionState.DISCONNECTED].
     */
    fun connect(source: NmeaSource) {
        activeJob?.cancel()
        val mySession = ++session
        retryPending = false
        tracker.reset()
        _uiState.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                sourceName = source.name,
                rocket = tracker.state,
                lastRawLine = null,
                recentLines = emptyList(),
            )
        }
        // NOTE: with Dispatchers.Main.immediate this body starts executing
        // before launch returns, so the stale-session guard uses the token
        // captured above rather than comparing against activeJob.
        activeJob = scope.launch {
            source.events()
                .onCompletion { cause ->
                    // Normal completion means the source is done for good
                    // (simulator finished, stream ended). Cancellation is
                    // handled by whoever cancelled; a newer session owns the
                    // state now.
                    if (cause == null && mySession == session) {
                        _uiState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
                    }
                }
                .collect { event ->
                    if (mySession == session) {
                        onEvent(event)
                    }
                }
        }
    }

    /**
     * Stops the active source, if any, and publishes
     * [ConnectionState.DISCONNECTED].
     *
     * Deliberately keeps the last rocket state, source name, and console
     * lines visible — after a disconnect in the field the last known rocket
     * position is exactly what the user still needs.
     */
    fun disconnect() {
        session++
        activeJob?.cancel()
        activeJob = null
        _uiState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
    }

    private fun onEvent(event: SourceEvent) {
        when (event) {
            is SourceEvent.Connected -> {
                retryPending = false
                _uiState.update { it.copy(connectionState = ConnectionState.CONNECTED) }
            }

            is SourceEvent.Disconnected ->
                _uiState.update {
                    it.copy(
                        // A Disconnected during a retry backoff is transient;
                        // the source reconnects by itself.
                        connectionState = if (retryPending) {
                            ConnectionState.CONNECTING
                        } else {
                            ConnectionState.DISCONNECTED
                        },
                    )
                }

            is SourceEvent.LineReceived -> {
                val result = parser.parse(event.raw)
                val rocket = tracker.onResult(result, nowMs = System.currentTimeMillis())
                _uiState.update {
                    it.copy(
                        rocket = rocket,
                        lastRawLine = event.raw,
                        recentLines = appendLine(it.recentLines, event.raw),
                    )
                }
            }

            is SourceEvent.SourceError -> {
                retryPending = event.willRetry
                _uiState.update {
                    it.copy(
                        // willRetry means the source is backing off to
                        // reconnect on its own; reflect that as CONNECTING.
                        connectionState = if (event.willRetry) {
                            ConnectionState.CONNECTING
                        } else {
                            ConnectionState.DISCONNECTED
                        },
                        recentLines = appendLine(it.recentLines, "[error] ${event.message}"),
                    )
                }
            }
        }
    }

    /** Appends [line], keeping at most [MAX_RECENT_LINES] entries, newest last. */
    private fun appendLine(lines: List<String>, line: String): List<String> {
        val trimmed = if (lines.size >= MAX_RECENT_LINES) {
            lines.drop(lines.size - MAX_RECENT_LINES + 1)
        } else {
            lines
        }
        return trimmed + line
    }

    private companion object {
        /** Console ring buffer capacity. */
        const val MAX_RECENT_LINES = 100
    }
}

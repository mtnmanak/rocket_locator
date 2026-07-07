package io.github.mtnmanak.rocketlocator26.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mtnmanak.rocketlocator26.core.flight.FlightTracker
import io.github.mtnmanak.rocketlocator26.core.flight.RocketState
import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import io.github.mtnmanak.rocketlocator26.core.nmea.NmeaParser
import io.github.mtnmanak.rocketlocator26.core.sim.FlightParams
import io.github.mtnmanak.rocketlocator26.core.sim.SyntheticFlight
import io.github.mtnmanak.rocketlocator26.transport.SimulatorSource
import io.github.mtnmanak.rocketlocator26.transport.SourceEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the home screen needs to render, derived from one telemetry source.
 *
 * @property connected whether the source currently reports an open link.
 * @property rocket latest tracked rocket state (position, altitudes, fix info, path).
 * @property sourceName human-readable name of the active telemetry source.
 * @property lastRawLine most recent raw NMEA line, for the live ticker footer.
 */
data class UiState(
    val connected: Boolean,
    val rocket: RocketState,
    val sourceName: String,
    val lastRawLine: String?,
)

/**
 * Drives the home screen from a simulated flight, proving the full pipeline:
 * source events -> NMEA parsing -> flight tracking -> UI state.
 *
 * The simulated flight launches from Black Rock Desert, reaches 3048 m AGL
 * (10,000 ft), and drifts 800 m southeast under drogue/main. Time is scaled
 * 10x so a full flight plays out in about 20 seconds, and the flight loops
 * forever with a short pause between replays.
 */
class HomeViewModel : ViewModel() {

    private val parser = NmeaParser()
    private val tracker = FlightTracker()

    private val source = SimulatorSource(
        flight = SyntheticFlight(
            FlightParams(
                launch = GeoPoint(latitude = 40.8838, longitude = -119.1178),
                launchAltitudeMslMeters = 1191.0,
                apogeeAglMeters = 3048.0,
                ascentSeconds = 25.0,
                descentSeconds = 180.0,
                driftBearingDeg = 135.0,
                driftDistanceMeters = 800.0,
            ),
        ),
        timeScale = TIME_SCALE,
    )

    private val _uiState = MutableStateFlow(
        UiState(
            connected = false,
            rocket = tracker.state,
            sourceName = SOURCE_NAME,
            lastRawLine = null,
        ),
    )

    /** Observable UI state; always holds the latest snapshot. */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Run the demo flight only while the UI is actually collecting
            // (collectAsStateWithLifecycle unsubscribes when backgrounded) —
            // otherwise the loop would parse NMEA forever with the screen off.
            _uiState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) {
                        replayForever()
                    }
                }
        }
    }

    private suspend fun replayForever() {
        // Each replay is a fresh flight: without the reset, path points and
        // max altitude from the previous loop would leak into the next one.
        while (true) {
            tracker.reset()
            _uiState.update { it.copy(rocket = tracker.state, lastRawLine = null) }
            source.events().collect { event -> onSourceEvent(event) }
            _uiState.update { it.copy(connected = false) }
            delay(REPLAY_PAUSE_MS)
        }
    }

    private fun onSourceEvent(event: SourceEvent) {
        when (event) {
            is SourceEvent.Connected ->
                _uiState.update { it.copy(connected = true) }

            is SourceEvent.Disconnected ->
                _uiState.update { it.copy(connected = false) }

            is SourceEvent.LineReceived -> {
                val result = parser.parse(event.raw)
                val rocket = tracker.onResult(result, nowMs = System.currentTimeMillis())
                _uiState.update { it.copy(rocket = rocket, lastRawLine = event.raw) }
            }

            is SourceEvent.SourceError -> {
                // Simulator errors are not actionable in the demo shell; the
                // connected flag already reflects link state via Disconnected.
            }
        }
    }

    private companion object {
        const val SOURCE_NAME = "Simulator"
        const val TIME_SCALE = 10.0
        const val REPLAY_PAUSE_MS = 2_000L
    }
}

package io.github.mtnmanak.rocketlocator26.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.mtnmanak.rocketlocator26.RocketLocatorApp
import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import io.github.mtnmanak.rocketlocator26.core.sim.FlightParams
import io.github.mtnmanak.rocketlocator26.core.sim.SyntheticFlight
import io.github.mtnmanak.rocketlocator26.service.TrackingService
import io.github.mtnmanak.rocketlocator26.telemetry.TelemetryUiState
import io.github.mtnmanak.rocketlocator26.transport.SimulatorSource
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin adapter between the home screen and the process-wide
 * [io.github.mtnmanak.rocketlocator26.telemetry.TelemetryRepository].
 *
 * The pipeline itself lives in the Application (and is kept alive in the
 * background by [TrackingService]), so this ViewModel holds no telemetry
 * state of its own — it only exposes the repository's state flow and a
 * couple of user actions.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as RocketLocatorApp).telemetry

    /** Observable telemetry snapshot for the home screen. */
    val uiState: StateFlow<TelemetryUiState> = repository.uiState

    /**
     * Plays one demo flight through the full pipeline: a launch from Black
     * Rock Desert to 3048 m AGL (10,000 ft) drifting 800 m southeast under
     * drogue/main, time-scaled 10x so it completes in about 20 seconds.
     */
    fun runSimulator() {
        repository.connect(
            SimulatorSource(
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
                timeScale = SIMULATOR_TIME_SCALE,
            ),
        )
    }

    /**
     * Stops the active telemetry session. Also stops [TrackingService] in
     * case a Bluetooth session is running under the foreground service;
     * stopping it when it isn't running is harmless.
     */
    fun disconnect() {
        TrackingService.stop(getApplication())
        repository.disconnect()
    }

    private companion object {
        /** Demo playback speed: 10x real time. */
        const val SIMULATOR_TIME_SCALE = 10.0
    }
}

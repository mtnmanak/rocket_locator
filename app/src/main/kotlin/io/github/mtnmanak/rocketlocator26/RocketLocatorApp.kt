package io.github.mtnmanak.rocketlocator26

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.mtnmanak.rocketlocator26.service.TrackingService
import io.github.mtnmanak.rocketlocator26.telemetry.TelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application entry point for GPS Rocket Locator '26.
 *
 * Owns the process-wide [TelemetryRepository] so the telemetry pipeline
 * survives configuration changes and keeps running under
 * [TrackingService] while the app is backgrounded.
 */
class RocketLocatorApp : Application() {

    /**
     * Process-lifetime scope for the telemetry pipeline. Main-immediate
     * because [TelemetryRepository] requires single-threaded (main)
     * consumption, and never cancelled: it lives as long as the process.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Shared telemetry pipeline, consumed by the UI and [TrackingService]. */
    val telemetry: TelemetryRepository by lazy { TelemetryRepository(applicationScope) }

    override fun onCreate() {
        super.onCreate()
        createTrackingNotificationChannel()
    }

    /**
     * Creates the low-importance channel used by [TrackingService]'s
     * foreground notification. minSdk is 26, so [NotificationChannel] is
     * always available. Idempotent: recreating an existing channel is a
     * no-op.
     */
    private fun createTrackingNotificationChannel() {
        val channel = NotificationChannel(
            TrackingService.CHANNEL_ID,
            "Tracking",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

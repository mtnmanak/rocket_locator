package io.github.mtnmanak.rocketlocator26.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import io.github.mtnmanak.rocketlocator26.MainActivity
import io.github.mtnmanak.rocketlocator26.RocketLocatorApp
import io.github.mtnmanak.rocketlocator26.telemetry.ConnectionState
import io.github.mtnmanak.rocketlocator26.telemetry.TelemetryRepository
import io.github.mtnmanak.rocketlocator26.telemetry.TelemetryUiState
import io.github.mtnmanak.rocketlocator26.transport.BleUartSource
import io.github.mtnmanak.rocketlocator26.transport.ClassicSppSource
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Bluetooth link and NMEA parsing alive
 * while the screen is off or the app is backgrounded.
 *
 * The service does not own the pipeline — it merely hands a Bluetooth
 * [io.github.mtnmanak.rocketlocator26.transport.NmeaSource] to the
 * process-wide [TelemetryRepository] and holds a `connectedDevice`
 * foreground claim so Android doesn't kill the link. While running it
 * mirrors the telemetry state into its notification (at most one update
 * every 5 seconds).
 *
 * Start with [startClassic] or [startBle]; stop with [stop] or the
 * notification's Disconnect action. Returns
 * [Service.START_NOT_STICKY]: a service killed by the system must not
 * resurrect itself and silently reconnect Bluetooth without the user asking.
 */
class TrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var notificationJob: Job? = null

    /** Watches for the terminal DISCONNECTED state to end the service. */
    private var stopWatchJob: Job? = null

    private val telemetry: TelemetryRepository
        get() = (application as RocketLocatorApp).telemetry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            else -> startTracking(intent)
        }
        return START_NOT_STICKY
    }

    /**
     * Cancels the service scope only. Deliberately does NOT disconnect the
     * telemetry pipeline here: onDestroy also runs when the system reclaims
     * the service (e.g. task swipe), and tearing down the Bluetooth link
     * then would kill an active recovery. The pipeline is torn down only on
     * an explicit stop ([ACTION_STOP] / [stop]).
     */
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTracking(intent: Intent?) {
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        if (address.isNullOrBlank()) {
            // Nothing to connect to — but startForegroundService() was called,
            // so startForeground() MUST happen before stopping or the OS
            // throws a RemoteServiceException.
            startForegroundCompat(buildNotification(telemetry.uiState.value))
            stopTracking()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME)
        val source = when (intent.getStringExtra(EXTRA_TYPE)) {
            TYPE_BLE -> BleUartSource(applicationContext, address, name)
            else -> ClassicSppSource(applicationContext, address, name)
        }
        telemetry.connect(source)

        startForegroundCompat(buildNotification(telemetry.uiState.value))

        // Mirror telemetry into the notification. StateFlow conflates, so a
        // plain collect-then-delay loop always renders the latest snapshot
        // while updating at most once every NOTIFICATION_UPDATE_MS.
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            telemetry.uiState.collect { state ->
                manager.notify(NOTIFICATION_ID, buildNotification(state))
                delay(NOTIFICATION_UPDATE_MS)
            }
        }

        // DISCONNECTED is terminal by contract (retry backoffs read as
        // CONNECTING): fatal source errors and completed streams must not
        // leave a zombie foreground service holding a connectedDevice claim.
        stopWatchJob?.cancel()
        stopWatchJob = serviceScope.launch {
            telemetry.uiState.first { it.connectionState == ConnectionState.DISCONNECTED }
            stopTracking()
        }
    }

    private fun stopTracking() {
        // Cancel the mirror loop BEFORE removing the notification: a pending
        // notify() after stopForeground would re-post an ongoing notification
        // with no service behind it — undismissable until force-stop.
        notificationJob?.cancel()
        notificationJob = null
        stopWatchJob?.cancel()
        stopWatchJob = null
        telemetry.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: TelemetryUiState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Disconnect",
            stopIntent,
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(state.sourceName ?: "Rocket tracker")
            .setContentText(notificationText(state))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(disconnectAction)
            .build()
    }

    /** e.g. "Connected · 1234 m AGL · 9 sats" or "Connecting…". */
    private fun notificationText(state: TelemetryUiState): String {
        val parts = mutableListOf(
            when (state.connectionState) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.DISCONNECTED -> "Disconnected"
            },
        )
        state.rocket.altitudeAglMeters?.let { parts += "${it.roundToInt()} m AGL" }
        state.rocket.satellitesInUse?.let { parts += "$it sats" }
        return parts.joinToString(" · ")
    }

    companion object {
        /** Notification channel id; the channel is created in [RocketLocatorApp.onCreate]. */
        const val CHANNEL_ID = "tracking"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_UPDATE_MS = 5_000L

        private const val ACTION_STOP = "io.github.mtnmanak.rocketlocator26.service.action.STOP"

        private const val EXTRA_TYPE = "extra_type"
        private const val EXTRA_ADDRESS = "extra_address"
        private const val EXTRA_NAME = "extra_name"

        private const val TYPE_CLASSIC = "classic"
        private const val TYPE_BLE = "ble"

        /**
         * Starts (or re-targets) tracking over Bluetooth Classic SPP.
         *
         * @param address Bluetooth MAC address of the paired tracker.
         * @param name human-readable device name for the UI/notification.
         */
        fun startClassic(context: Context, address: String, name: String?) {
            start(context, TYPE_CLASSIC, address, name)
        }

        /**
         * Starts (or re-targets) tracking over BLE Nordic UART.
         *
         * @param address Bluetooth MAC address of the tracker.
         * @param name human-readable device name for the UI/notification.
         */
        fun startBle(context: Context, address: String, name: String?) {
            start(context, TYPE_BLE, address, name)
        }

        /** Disconnects telemetry and stops the foreground service. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP),
            )
        }

        private fun start(context: Context, type: String, address: String, name: String?) {
            val intent = Intent(context, TrackingService::class.java)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_NAME, name)
            context.startForegroundService(intent)
        }
    }
}

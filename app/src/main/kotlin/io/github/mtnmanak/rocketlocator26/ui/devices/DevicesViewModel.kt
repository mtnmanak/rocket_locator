package io.github.mtnmanak.rocketlocator26.ui.devices

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mtnmanak.rocketlocator26.RocketLocatorApp
import io.github.mtnmanak.rocketlocator26.service.TrackingService
import io.github.mtnmanak.rocketlocator26.telemetry.TelemetryUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A bonded (Classic) Bluetooth device, e.g. an Eggfinder HC-06 receiver.
 *
 * @property name user-visible device name, or null when unreadable.
 * @property address MAC address, e.g. "00:14:03:05:59:C2".
 */
data class PairedDevice(
    val name: String?,
    val address: String,
)

/**
 * A device seen during a BLE scan, e.g. an HM-10/BT-04-style FFE0 module.
 *
 * @property name advertised or GATT name, or null when the advertisement
 *   carried none.
 * @property address MAC address the scan reported.
 * @property rssi last observed signal strength in dBm.
 */
data class ScannedDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
)

/**
 * State holder for the device-picker screen.
 *
 * Responsibilities:
 * - Runtime-permission model: [requiredPermissions] is what the screen should
 *   request; [hasPermissions] reflects only the permissions that actually
 *   gate Bluetooth work (POST_NOTIFICATIONS is requested opportunistically on
 *   API 33+ but its denial never blocks anything).
 * - Bonded-device listing for Bluetooth Classic receivers.
 * - BLE scanning with per-address dedupe and a [SCAN_DURATION_MS] auto-stop.
 * - Connect/disconnect actions delegated to [TrackingService].
 * - Pass-through of the repository's [TelemetryUiState] for the live console.
 */
class DevicesViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothAdapter: BluetoothAdapter? =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** False when the device has no Bluetooth hardware at all. */
    val hasBluetoothHardware: Boolean = bluetoothAdapter != null

    private val _hasPermissions = MutableStateFlow(hasBlockingPermissions())

    /** True when every permission that gates Bluetooth work is granted. */
    val hasPermissions: StateFlow<Boolean> = _hasPermissions.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(readBluetoothEnabled())

    /**
     * True while the Bluetooth adapter is on. When false, bonded devices read
     * as an empty list and BLE scans return nothing — the screen must say so
     * instead of showing a silently empty picker.
     */
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(readLocationEnabled())

    /**
     * True when the system Location toggle is on, or trivially on API 31+
     * where BLE scanning no longer depends on it (`neverForLocation`). On
     * API 26–30 a scan with Location off "succeeds" but finds nothing.
     */
    val isLocationEnabled: StateFlow<Boolean> = _isLocationEnabled.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())

    /** Bonded Classic devices; empty when unreadable or none are paired. */
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())

    /** BLE scan results, deduplicated by address, in first-seen order. */
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)

    /** True while a BLE scan is running. */
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Live telemetry state, for the connection card and NMEA console. */
    val telemetryUiState: StateFlow<TelemetryUiState> =
        (application as RocketLocatorApp).telemetry.uiState

    private var scanStopJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::addScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
        }
    }

    init {
        refresh()
    }

    /**
     * Permissions the screen should request before Bluetooth work.
     *
     * API 33+: BLUETOOTH_CONNECT + BLUETOOTH_SCAN, plus POST_NOTIFICATIONS
     * requested alongside (denying notifications never blocks Bluetooth).
     * API 31-32: BLUETOOTH_CONNECT + BLUETOOTH_SCAN.
     * API 26-30: ACCESS_FINE_LOCATION, required for Bluetooth discovery/scan;
     * connect-only paths on those versions need no runtime permission.
     */
    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Re-checks permissions and the Bluetooth/Location environment, and
     * reloads the bonded-device list. Call on screen resume: the user may
     * have granted permissions or flipped toggles in system Settings.
     */
    fun refresh() {
        _hasPermissions.value = hasBlockingPermissions()
        _isBluetoothEnabled.value = readBluetoothEnabled()
        _isLocationEnabled.value = readLocationEnabled()
        _pairedDevices.value = loadPairedDevices()
    }

    /**
     * Starts a BLE scan (low-latency mode) that auto-stops after
     * [SCAN_DURATION_MS]. No-op while already scanning, when the adapter is
     * missing/off, or when the scan permission is denied.
     */
    fun startBleScan() {
        if (_isScanning.value) return
        // Re-read the environment so the gating cards are current even if
        // the user tapped Scan straight after flipping a system toggle.
        refresh()
        if (!_isBluetoothEnabled.value || !_isLocationEnabled.value) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        _scanResults.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (_: SecurityException) {
            return
        } catch (_: IllegalStateException) {
            // Adapter turned off between the null check and the call.
            return
        }
        _isScanning.value = true
        scanStopJob?.cancel()
        scanStopJob = viewModelScope.launch {
            delay(SCAN_DURATION_MS)
            stopScanInternal()
        }
    }

    /** Stops a running BLE scan immediately. Safe to call when not scanning. */
    fun stopBleScan() {
        scanStopJob?.cancel()
        scanStopJob = null
        stopScanInternal()
    }

    /** Connects to a Bluetooth Classic (SPP) receiver via [TrackingService]. */
    fun connectClassic(address: String, name: String?) {
        stopBleScan()
        TrackingService.startClassic(getApplication(), address, name)
    }

    /** Connects to a BLE (FFE0-style) receiver via [TrackingService]. */
    fun connectBle(address: String, name: String?) {
        stopBleScan()
        TrackingService.startBle(getApplication(), address, name)
    }

    /** Tears down whatever connection the service currently holds. */
    fun disconnect() {
        TrackingService.stop(getApplication())
    }

    override fun onCleared() {
        stopBleScan()
        super.onCleared()
    }

    /**
     * Checks only the permissions whose denial actually blocks Bluetooth
     * work; intentionally narrower than [requiredPermissions] because
     * POST_NOTIFICATIONS must never gate anything.
     */
    private fun hasBlockingPermissions(): Boolean {
        val blocking = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return blocking.all { permission ->
            ContextCompat.checkSelfPermission(getApplication(), permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun readBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    private fun readLocationEnabled(): Boolean {
        // API 31+ scans with neverForLocation don't need the Location toggle.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val manager =
            getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun loadPairedDevices(): List<PairedDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return try {
            adapter.bondedDevices
                .orEmpty()
                .map { device -> PairedDevice(name = device.name, address = device.address) }
                .sortedBy { it.name ?: it.address }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun addScanResult(result: ScanResult) {
        val address = result.device?.address ?: return
        val name = try {
            result.device.name ?: result.scanRecord?.deviceName
        } catch (_: SecurityException) {
            null
        }
        _scanResults.update { current ->
            val index = current.indexOfFirst { it.address == address }
            if (index >= 0) {
                // Keep a previously seen name if this advertisement lacks one.
                val merged = ScannedDevice(
                    name = name ?: current[index].name,
                    address = address,
                    rssi = result.rssi,
                )
                current.toMutableList().apply { this[index] = merged }
            } else {
                current + ScannedDevice(name = name, address = address, rssi = result.rssi)
            }
        }
    }

    private fun stopScanInternal() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission revoked mid-scan; the framework stops it for us.
        } catch (_: IllegalStateException) {
            // Adapter already off; nothing left to stop.
        }
        _isScanning.value = false
    }

    private companion object {
        /** BLE scan auto-stop; FFE0 modules advertise every few hundred ms. */
        const val SCAN_DURATION_MS = 12_000L
    }
}

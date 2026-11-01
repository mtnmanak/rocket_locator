package io.github.mtnmanak.rocketlocator26.ui.devices

import androidx.compose.ui.graphics.Color
import io.github.mtnmanak.rocketlocator26.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-permission history avoids treating an unrequested permission as permanently denied. */
internal data class PermissionStatus(
    val granted: Boolean,
    val requestedBefore: Boolean,
    val showRationale: Boolean,
)

internal fun needsPermissionSettings(permissions: List<PermissionStatus>): Boolean =
    permissions.any { !it.granted && it.requestedBefore && !it.showRationale }

/** Preserve Android's error code alongside localizable recovery guidance. */
data class BleScanError(val code: Int, val messageRes: Int)

internal fun bleScanError(code: Int): BleScanError = BleScanError(
    code,
    // Android ScanCallback error codes; kept as values so this policy needs no Android runtime.
    when (code) {
        1 -> R.string.scan_error_already_started
        2 -> R.string.scan_error_registration
        3 -> R.string.scan_error_internal
        4 -> R.string.scan_error_unsupported
        5 -> R.string.scan_error_resources
        6 -> R.string.scan_error_too_frequent
        else -> R.string.scan_error_unknown
    },
)

/** A blank advertised name provides no useful association for TalkBack. */
internal fun deviceConnectTarget(name: String?, address: String): String =
    name?.takeIf { it.isNotBlank() } ?: address

/** Scan failure stays visible until another attempt, including after an explicit Stop. */
internal class BleScanStatus {
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()
    private val _error = MutableStateFlow<BleScanError?>(null)
    val error = _error.asStateFlow()

    fun started() {
        _error.value = null
        _isScanning.value = true
    }

    fun failed(code: Int) {
        _error.value = bleScanError(code)
        _isScanning.value = false
    }

    fun stopped() {
        _isScanning.value = false
    }
}

internal fun devicesOutlinedLabelColor(darkTheme: Boolean, onSurface: Color, defaultColor: Color): Color =
    if (darkTheme) onSurface else defaultColor

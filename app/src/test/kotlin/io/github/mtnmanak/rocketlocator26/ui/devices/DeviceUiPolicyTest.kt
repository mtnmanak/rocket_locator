package io.github.mtnmanak.rocketlocator26.ui.devices

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.mtnmanak.rocketlocator26.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DeviceUiPolicyTest {
    @Test
    fun firstDenialCanRetryAndPermanentDenialNeedsSettings() {
        assertFalse(needsPermissionSettings(listOf(PermissionStatus(false, false, false))))
        assertFalse(needsPermissionSettings(listOf(PermissionStatus(false, true, true))))
        assertTrue(needsPermissionSettings(listOf(PermissionStatus(false, true, false))))
    }

    @Test
    fun settingsGrantClearsDeadEndEvenWhenRationaleRemainsFalse() {
        assertFalse(needsPermissionSettings(listOf(PermissionStatus(true, true, false))))
        assertTrue(
            needsPermissionSettings(
                listOf(
                    PermissionStatus(true, true, false),
                    PermissionStatus(false, true, false),
                ),
            ),
        )
        assertFalse(
            needsPermissionSettings(
                listOf(
                    PermissionStatus(true, true, false),
                    PermissionStatus(false, false, false),
                ),
            ),
        )
    }

    @ParameterizedTest
    @CsvSource("Tracker,AA:BB,Tracker", ",AA:BB,AA:BB", "'   ',AA:BB,AA:BB")
    fun connectAccessibilityTargetUsesNameOrAddress(
        name: String?,
        address: String,
        expected: String,
    ) {
        assertEquals(expected, deviceConnectTarget(name, address))
    }

    @Test
    fun scanFailureRetainsCodeAndSpecificRecoveryMessage() {
        val messages =
            mapOf(
                1 to R.string.scan_error_already_started,
                2 to R.string.scan_error_registration,
                3 to R.string.scan_error_internal,
                4 to R.string.scan_error_unsupported,
                5 to R.string.scan_error_resources,
                6 to R.string.scan_error_too_frequent,
                99 to R.string.scan_error_unknown,
            )
        messages.forEach { (code, messageRes) ->
            assertEquals(BleScanError(code, messageRes), bleScanError(code))
        }
    }

    @Test
    fun scanFailurePublishesErrorStopsScanningAndRetryClearsError() {
        val status = BleScanStatus()
        status.started()
        assertTrue(status.isScanning.value)
        status.failed(2)
        assertFalse(status.isScanning.value)
        assertEquals(BleScanError(2, R.string.scan_error_registration), status.error.value)
        status.stopped()
        assertEquals(2, status.error.value?.code, "Stopping must not hide the failure behind an empty-results hint")
        status.started()
        assertTrue(status.isScanning.value)
        assertEquals(null, status.error.value)
        status.failed(6)
        assertEquals(BleScanError(6, R.string.scan_error_too_frequent), status.error.value)
    }

    @Test
    fun darkOutlinedLabelsMeetContrastAndLightLabelsKeepDefault() {
        val paper = Color(0xFFF6FAFF)
        val navyRaised = Color(0xFF123859)
        val brandOrange = Color(0xFFFF6509)
        val darkLabel = devicesOutlinedLabelColor(true, paper, brandOrange)
        val ratio = (darkLabel.luminance() + 0.05) / (navyRaised.luminance() + 0.05)
        assertTrue(ratio >= 4.5, "Dark outlined labels must meet WCAG AA")
        assertEquals(11.54, ratio, 0.01)
        val lightDefault = Color(0xFFBB4100)
        assertEquals(lightDefault, devicesOutlinedLabelColor(false, Color(0xFF03254F), lightDefault))
    }
}

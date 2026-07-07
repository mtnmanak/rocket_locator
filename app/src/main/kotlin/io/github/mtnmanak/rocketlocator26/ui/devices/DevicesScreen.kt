package io.github.mtnmanak.rocketlocator26.ui.devices

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mtnmanak.rocketlocator26.R
import io.github.mtnmanak.rocketlocator26.telemetry.ConnectionState
import io.github.mtnmanak.rocketlocator26.ui.ConnectionStateChip

/**
 * Device picker: permission gate, connection status, paired Classic devices,
 * BLE scan results, and a live NMEA console.
 *
 * @param onBack invoked by the top-bar navigation icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPermissions by viewModel.hasPermissions.collectAsStateWithLifecycle()
    val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsStateWithLifecycle()
    val isLocationEnabled by viewModel.isLocationEnabled.collectAsStateWithLifecycle()
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetryUiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Whatever the outcome, re-derive gating state; a permanent denial
        // simply leaves the rationale card visible without re-prompting.
        viewModel.refresh()
    }

    // Permissions/toggles may change in system Settings while we're paused.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // Classic connect on API 26-30 needs no runtime permission; on 31+ it is
    // covered by the same BLUETOOTH_CONNECT grant that gates everything else.
    val canConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermissions

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasPermissions) {
                PermissionCard(
                    onGrant = { permissionLauncher.launch(viewModel.requiredPermissions()) },
                )
            }
            if (viewModel.hasBluetoothHardware && !isBluetoothEnabled) {
                EnvironmentCard(
                    title = stringResource(R.string.devices_bluetooth_off_title),
                    body = stringResource(R.string.devices_bluetooth_off_body),
                )
            }
            if (!isLocationEnabled) {
                // Only ever false on Android 8-11, where BLE scanning
                // silently finds nothing with the Location toggle off.
                EnvironmentCard(
                    title = stringResource(R.string.devices_location_off_title),
                    body = stringResource(R.string.devices_location_off_body),
                )
            }
            ConnectionStatusCard(
                connectionState = telemetry.connectionState,
                sourceName = telemetry.sourceName,
                onDisconnect = viewModel::disconnect,
            )
            PairedDevicesCard(
                hasBluetoothHardware = viewModel.hasBluetoothHardware,
                devices = pairedDevices,
                connectEnabled = canConnect,
                onConnect = viewModel::connectClassic,
            )
            BleScanCard(
                hasBluetoothHardware = viewModel.hasBluetoothHardware,
                results = scanResults,
                isScanning = isScanning,
                scanEnabled = hasPermissions && isBluetoothEnabled && isLocationEnabled,
                connectEnabled = canConnect,
                onStartScan = viewModel::startBleScan,
                onStopScan = viewModel::stopBleScan,
                onConnect = viewModel::connectBle,
            )
            NmeaConsoleCard(lines = telemetry.recentLines)
        }
    }
}

/** Flags a system-level blocker (Bluetooth off, Location off) the app cannot fix itself. */
@Composable
private fun EnvironmentCard(title: String, body: String) {
    SectionCard(title = title) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Explains why Bluetooth permissions are needed, with a grant action. */
@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    SectionCard(title = stringResource(R.string.devices_permission_title)) {
        Text(
            text = stringResource(R.string.devices_permission_rationale),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onGrant) {
            Text(stringResource(R.string.btn_grant_permission))
        }
    }
}

/** Current source and link state, with disconnect while a link is active. */
@Composable
private fun ConnectionStatusCard(
    connectionState: ConnectionState,
    sourceName: String?,
    onDisconnect: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.card_connection)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionStateChip(
                connectionState = connectionState,
                sourceName = sourceName,
                modifier = Modifier.weight(1f),
            )
            if (connectionState != ConnectionState.DISCONNECTED) {
                OutlinedButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.btn_disconnect))
                }
            }
        }
    }
}

/** Bonded Classic (SPP) devices with per-device connect actions. */
@Composable
private fun PairedDevicesCard(
    hasBluetoothHardware: Boolean,
    devices: List<PairedDevice>,
    connectEnabled: Boolean,
    onConnect: (address: String, name: String?) -> Unit,
) {
    SectionCard(title = stringResource(R.string.devices_section_paired)) {
        Text(
            text = stringResource(R.string.devices_paired_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            !hasBluetoothHardware -> EmptyHint(stringResource(R.string.devices_no_bluetooth))
            devices.isEmpty() -> EmptyHint(stringResource(R.string.devices_none_paired))
            else -> devices.forEach { device ->
                DeviceRow(
                    name = device.name,
                    address = device.address,
                    detail = null,
                    connectEnabled = connectEnabled,
                    onConnect = { onConnect(device.address, device.name) },
                )
            }
        }
    }
}

/** BLE scan controls and deduplicated results with connect actions. */
@Composable
private fun BleScanCard(
    hasBluetoothHardware: Boolean,
    results: List<ScannedDevice>,
    isScanning: Boolean,
    scanEnabled: Boolean,
    connectEnabled: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (address: String, name: String?) -> Unit,
) {
    SectionCard(title = stringResource(R.string.devices_section_ble)) {
        if (!hasBluetoothHardware) {
            EmptyHint(stringResource(R.string.devices_no_bluetooth))
            return@SectionCard
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isScanning) {
                OutlinedButton(onClick = onStopScan) {
                    Text(stringResource(R.string.btn_stop_scan))
                }
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Button(onClick = onStartScan, enabled = scanEnabled) {
                    Text(stringResource(R.string.btn_scan))
                }
            }
        }
        if (results.isEmpty()) {
            EmptyHint(stringResource(R.string.devices_no_scan_results))
        } else {
            results.forEach { device ->
                DeviceRow(
                    name = device.name,
                    address = device.address,
                    detail = stringResource(R.string.devices_rssi_dbm, device.rssi),
                    connectEnabled = connectEnabled,
                    onConnect = { onConnect(device.address, device.name) },
                )
            }
        }
    }
}

/**
 * Live NMEA console: last lines from the repository, monospace, fixed
 * height, auto-scrolled to the newest line. Lines the transport injects as
 * "[error] ..." render in the error color.
 */
@Composable
private fun NmeaConsoleCard(lines: List<String>) {
    SectionCard(title = stringResource(R.string.devices_section_console)) {
        if (lines.isEmpty()) {
            EmptyHint(stringResource(R.string.devices_console_empty))
        } else {
            val listState = rememberLazyListState()
            // Follow the tail only while the user is at the bottom: a manual
            // scroll-up pauses following (so [error] lines stay readable) and
            // returning to the bottom resumes it. Keyed on the list instance,
            // not its size — the 100-line ring buffer pins size once full.
            LaunchedEffect(lines) {
                if (!listState.canScrollForward) {
                    listState.scrollToItem(lines.lastIndex)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CONSOLE_HEIGHT),
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = if (line.startsWith(ERROR_LINE_PREFIX)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** One device entry: name/address (and optional detail) plus Connect. */
@Composable
private fun DeviceRow(
    name: String?,
    address: String,
    detail: String?,
    connectEnabled: Boolean,
    onConnect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name ?: stringResource(R.string.devices_unnamed),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (detail != null) "$address · $detail" else address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onConnect, enabled = connectEnabled) {
            Text(stringResource(R.string.btn_connect))
        }
    }
}

/** Section container matching the home screen's card styling. */
@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val CONSOLE_HEIGHT = 200.dp
private const val ERROR_LINE_PREFIX = "[error]"

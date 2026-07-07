package io.github.mtnmanak.rocketlocator26.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mtnmanak.rocketlocator26.R
import io.github.mtnmanak.rocketlocator26.core.flight.RocketState
import io.github.mtnmanak.rocketlocator26.telemetry.ConnectionState
import io.github.mtnmanak.rocketlocator26.telemetry.TelemetryUiState
import java.util.Locale

/**
 * Home screen: observes [HomeViewModel.uiState] and renders it.
 * All rendering is delegated to the stateless [HomeContent].
 *
 * @param onOpenDevices invoked when the user asks for the device picker
 *   (top-bar action or the "Devices" button).
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onRunSimulator = viewModel::runSimulator,
        onDisconnect = viewModel::disconnect,
        onOpenDevices = onOpenDevices,
        modifier = modifier,
    )
}

/** Stateless rendering of the home screen; previewable and testable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: TelemetryUiState,
    onRunSimulator: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenDevices) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.action_devices),
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
            ActionRow(
                connectionState = state.connectionState,
                sourceName = state.sourceName,
                onRunSimulator = onRunSimulator,
                onDisconnect = onDisconnect,
                onOpenDevices = onOpenDevices,
            )
            ConnectionCard(
                sourceName = state.sourceName,
                connectionState = state.connectionState,
                rocket = state.rocket,
            )
            PositionCard(rocket = state.rocket)
            AltitudeCard(rocket = state.rocket)
            PathCard(pathPointCount = state.rocket.path.size)
            NmeaTicker(lastRawLine = state.lastRawLine)
            Text(
                text = stringResource(R.string.footer_version),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Primary actions under the top bar.
 *
 * Disconnected: offer the simulator and the device picker. Connecting or
 * connected: offer disconnect, plus a color-coded chip naming the source.
 */
@Composable
private fun ActionRow(
    connectionState: ConnectionState,
    sourceName: String?,
    onRunSimulator: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (connectionState == ConnectionState.DISCONNECTED) {
            Button(onClick = onRunSimulator) {
                Text(stringResource(R.string.btn_run_simulator))
            }
            OutlinedButton(onClick = onOpenDevices) {
                Text(stringResource(R.string.action_devices))
            }
        } else {
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.btn_disconnect))
            }
            ConnectionStateChip(
                connectionState = connectionState,
                sourceName = sourceName,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Small tinted chip showing the active source name and link state. */
@Composable
internal fun ConnectionStateChip(
    connectionState: ConnectionState,
    sourceName: String?,
    modifier: Modifier = Modifier,
) {
    val color = connectionStateColor(connectionState)
    val label = connectionStateLabel(connectionState)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f),
        modifier = modifier,
    ) {
        Text(
            text = sourceName?.let { "$it · $label" } ?: label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Color coding shared by home and devices screens: green up, amber pending. */
@Composable
internal fun connectionStateColor(state: ConnectionState): Color = when (state) {
    ConnectionState.CONNECTED -> StatusGreen
    ConnectionState.CONNECTING -> StatusAmber
    ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Localized label for a [ConnectionState]. */
@Composable
internal fun connectionStateLabel(state: ConnectionState): String = stringResource(
    when (state) {
        ConnectionState.CONNECTED -> R.string.status_connected
        ConnectionState.CONNECTING -> R.string.status_connecting
        ConnectionState.DISCONNECTED -> R.string.status_disconnected
    },
)

@Composable
private fun ConnectionCard(
    sourceName: String?,
    connectionState: ConnectionState,
    rocket: RocketState,
) {
    TelemetryCard(title = stringResource(R.string.card_connection)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sourceName ?: stringResource(R.string.home_source_none),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = connectionStateLabel(connectionState),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (connectionState == ConnectionState.DISCONNECTED) {
                    MaterialTheme.colorScheme.error
                } else {
                    connectionStateColor(connectionState)
                },
            )
        }
        LabeledValue(
            label = stringResource(R.string.label_fix_quality),
            value = fixQualityText(rocket.fixQuality),
        )
        LabeledValue(
            label = stringResource(R.string.label_satellites),
            value = rocket.satellitesInUse?.toString() ?: PLACEHOLDER,
        )
    }
}

@Composable
private fun PositionCard(rocket: RocketState) {
    TelemetryCard(title = stringResource(R.string.card_position)) {
        LabeledValue(
            label = stringResource(R.string.label_latitude),
            value = rocket.position?.latitude?.let { formatDegrees(it) } ?: PLACEHOLDER,
            monospaceValue = true,
        )
        LabeledValue(
            label = stringResource(R.string.label_longitude),
            value = rocket.position?.longitude?.let { formatDegrees(it) } ?: PLACEHOLDER,
            monospaceValue = true,
        )
    }
}

@Composable
private fun AltitudeCard(rocket: RocketState) {
    TelemetryCard(title = stringResource(R.string.card_altitude)) {
        LabeledValue(
            label = stringResource(R.string.label_altitude_agl),
            value = rocket.altitudeAglMeters?.let { formatMeters(it) } ?: PLACEHOLDER,
            monospaceValue = true,
        )
        LabeledValue(
            label = stringResource(R.string.label_max_altitude_agl),
            value = rocket.maxAltitudeAglMeters?.let { formatMeters(it) } ?: PLACEHOLDER,
            monospaceValue = true,
        )
    }
}

@Composable
private fun PathCard(pathPointCount: Int) {
    TelemetryCard(title = stringResource(R.string.card_path)) {
        LabeledValue(
            label = stringResource(R.string.label_path_points),
            value = pathPointCount.toString(),
        )
    }
}

/** Live raw-NMEA footer: visible proof the source -> parser pipeline is flowing. */
@Composable
private fun NmeaTicker(lastRawLine: String?) {
    Text(
        text = lastRawLine ?: stringResource(R.string.ticker_waiting),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TelemetryCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
private fun LabeledValue(
    label: String,
    value: String,
    monospaceValue: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospaceValue) FontFamily.Monospace else null,
        )
    }
}

private const val PLACEHOLDER = "—"

/** Mid-tone green/amber chosen to stay legible on both light and dark surfaces. */
private val StatusGreen = Color(0xFF43A047)
private val StatusAmber = Color(0xFFF9A825)

private fun formatDegrees(degrees: Double): String =
    String.format(Locale.US, "%.6f°", degrees)

private fun formatMeters(meters: Double): String =
    String.format(Locale.US, "%.1f m", meters)

private fun fixQualityText(fixQuality: Int): String = when (fixQuality) {
    0 -> "No fix"
    1 -> "GPS"
    2 -> "DGPS"
    4 -> "RTK fixed"
    5 -> "RTK float"
    6 -> "Dead reckoning"
    else -> "Unknown ($fixQuality)"
}

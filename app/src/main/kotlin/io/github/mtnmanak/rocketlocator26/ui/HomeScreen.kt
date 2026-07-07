package io.github.mtnmanak.rocketlocator26.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mtnmanak.rocketlocator26.R
import io.github.mtnmanak.rocketlocator26.core.flight.RocketState
import java.util.Locale

/**
 * Home screen: observes [HomeViewModel.uiState] and renders it.
 * All rendering is delegated to the stateless [HomeContent].
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(state = state, modifier = modifier)
}

/** Stateless rendering of the home screen; previewable and testable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: UiState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
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
            ConnectionCard(
                sourceName = state.sourceName,
                connected = state.connected,
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

@Composable
private fun ConnectionCard(
    sourceName: String,
    connected: Boolean,
    rocket: RocketState,
) {
    val statusText = stringResource(
        if (connected) R.string.status_connected else R.string.status_disconnected,
    )
    val statusColor =
        if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    TelemetryCard(title = stringResource(R.string.card_connection)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sourceName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor,
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

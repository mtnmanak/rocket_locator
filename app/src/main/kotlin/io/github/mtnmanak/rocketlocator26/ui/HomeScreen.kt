package io.github.mtnmanak.rocketlocator26.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mtnmanak.rocketlocator26.R
import io.github.mtnmanak.rocketlocator26.core.flight.RocketState
import io.github.mtnmanak.rocketlocator26.core.flight.fixAgeMs
import io.github.mtnmanak.rocketlocator26.telemetry.ConnectionState
import io.github.mtnmanak.rocketlocator26.telemetry.TelemetryUiState
import io.github.mtnmanak.rocketlocator26.telemetry.needsSimulatorConfirmation
import io.github.mtnmanak.rocketlocator26.ui.theme.mmrColors
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
        onNewFlight = viewModel::newFlight,
    )
}

/** Telemetry rendering with app-owned confirmation dialogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: TelemetryUiState,
    onRunSimulator: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier,
    onNewFlight: () -> Unit = {},
) {
    var confirmNewFlight by rememberSaveable { mutableStateOf(false) }
    var confirmSimulator by rememberSaveable { mutableStateOf(false) }
    // Do not read the clock here: only the age Text reads each tick. Derived
    // stale labels recompose once at the threshold, not once per second.
    val now = rememberFixClock(state.rocket.position != null)
    if (confirmNewFlight || confirmSimulator) {
        val simulator = confirmSimulator
        AlertDialog(
            onDismissRequest = {
                confirmNewFlight = false
                confirmSimulator = false
            },
            title = { Text(stringResource(if (simulator) R.string.btn_run_simulator else R.string.btn_new_flight)) },
            text = {
                Text(stringResource(if (simulator) R.string.confirm_simulator else R.string.confirm_new_flight))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewFlight = false
                    confirmSimulator = false
                    if (simulator) onRunSimulator() else onNewFlight()
                }) {
                    Text(stringResource(if (simulator) R.string.btn_run_simulator else R.string.btn_new_flight))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmNewFlight = false
                    confirmSimulator = false
                }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionRow(
                connectionState = state.connectionState,
                sourceName = state.sourceName,
                onRunSimulator = {
                    if (state.needsSimulatorConfirmation()) confirmSimulator = true else onRunSimulator()
                },
                onDisconnect = onDisconnect,
                onOpenDevices = onOpenDevices,
            )
            OutlinedButton(onClick = { confirmNewFlight = true }) {
                Text(stringResource(R.string.btn_new_flight))
            }
            ConnectionCard(
                sourceName = state.sourceName,
                connectionState = state.connectionState,
                rocket = state.rocket,
                awaitingFix = state.awaitingFix,
                now = now,
            )
            PositionCard(rocket = state.rocket, awaitingFix = state.awaitingFix, now = now)
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
                colors =
                    ButtonDefaults.buttonColors(
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
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sourceName != null) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Color coding shared by home and devices screens: green up, amber pending. */
@Composable
internal fun connectionStateColor(state: ConnectionState): Color =
    when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.mmrColors.success
        ConnectionState.CONNECTING -> MaterialTheme.mmrColors.warning
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** Localized label for a [ConnectionState]. */
@Composable
internal fun connectionStateLabel(state: ConnectionState): String =
    stringResource(
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
    awaitingFix: Boolean,
    now: State<Long>,
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
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color =
                    if (connectionState == ConnectionState.DISCONNECTED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        connectionStateColor(connectionState)
                    },
            )
        }
        FixQualityValue(rocket, awaitingFix, now)
        LabeledValue(
            label = stringResource(R.string.label_satellites),
            value = rocket.satellitesInUse?.toString() ?: PLACEHOLDER,
        )
    }
}

@Composable
private fun PositionCard(
    rocket: RocketState,
    awaitingFix: Boolean,
    now: State<Long>,
) {
    TelemetryCard(
        title = stringResource(R.string.card_position),
        heading = { PositionHeading(rocket, awaitingFix, now) },
    ) {
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
        if (rocket.position != null) FixAgeText(rocket, awaitingFix, now)
    }
}

/** Lifecycle collection stops this cold flow while Home is off-screen. */
@Composable
private fun rememberFixClock(enabled: Boolean): State<Long> {
    val ticks =
        remember(enabled) {
            fixClockTicks(enabled)
        }
    return ticks.collectAsStateWithLifecycle(initialValue = SystemClock.elapsedRealtime())
}

@Composable
private fun staleState(
    rocket: RocketState,
    awaitingFix: Boolean,
    now: State<Long>,
): State<Boolean> =
    remember(rocket, awaitingFix, now) {
        derivedStateOf { isPositionStale(rocket, awaitingFix, now.value) }
    }

@Composable
private fun PositionHeading(
    rocket: RocketState,
    awaitingFix: Boolean,
    now: State<Long>,
) {
    val stale by staleState(rocket, awaitingFix, now)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stale) Icon(Icons.Filled.Warning, contentDescription = null)
        Text(
            text = stringResource(if (stale) R.string.card_last_position else R.string.card_position),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.mmrColors.accentText,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun FixQualityValue(
    rocket: RocketState,
    awaitingFix: Boolean,
    now: State<Long>,
) {
    val stale by staleState(rocket, awaitingFix, now)
    LabeledValue(
        label = stringResource(R.string.label_fix_quality),
        value = if (stale) stringResource(R.string.fix_no_current) else fixQualityText(rocket.fixQuality),
    )
}

@Composable
private fun FixAgeText(
    rocket: RocketState,
    awaitingFix: Boolean,
    now: State<Long>,
) {
    val ageMs = rocket.fixAgeMs(now.value)
    val stale = isPositionStale(rocket, awaitingFix, now.value)
    val age =
        ageMs?.let {
            formatFixAge(
                it,
                stringResource(R.string.age_seconds),
                stringResource(R.string.age_minutes),
                stringResource(R.string.age_hours),
            )
        }
    val spokenAge =
        ageMs?.let {
            val (value, unit) = fixAgeParts(it)
            val plural =
                when (unit) {
                    AgeUnit.SECONDS -> R.plurals.age_seconds_spoken
                    AgeUnit.MINUTES -> R.plurals.age_minutes_spoken
                    AgeUnit.HOURS -> R.plurals.age_hours_spoken
                }
            val count = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            pluralStringResource(plural, count, count)
        }
    val label = if (stale) R.string.fix_age_stale else R.string.fix_age_live
    val spoken = if (spokenAge == null) stringResource(R.string.fix_age_unknown) else stringResource(label, spokenAge)
    Text(
        text = if (age == null) stringResource(R.string.fix_age_unknown) else stringResource(label, age),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { contentDescription = spoken },
    )
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
    heading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (heading != null) {
                heading()
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.mmrColors.accentText,
                )
            }
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

private fun formatDegrees(degrees: Double): String = String.format(Locale.US, "%.6f°", degrees)

private fun formatMeters(meters: Double): String = String.format(Locale.US, "%.1f m", meters)

@Composable
private fun fixQualityText(fixQuality: Int): String =
    when (fixQuality) {
        0 -> stringResource(R.string.fix_none)
        1 -> stringResource(R.string.fix_gps)
        2 -> stringResource(R.string.fix_dgps)
        3 -> stringResource(R.string.fix_pps)
        4 -> stringResource(R.string.fix_rtk_fixed)
        5 -> stringResource(R.string.fix_rtk_float)
        6 -> stringResource(R.string.fix_dead_reckoning)
        else -> stringResource(R.string.fix_unknown, fixQuality)
    }

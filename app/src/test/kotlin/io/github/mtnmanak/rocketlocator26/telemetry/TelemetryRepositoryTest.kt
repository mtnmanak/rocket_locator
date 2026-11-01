package io.github.mtnmanak.rocketlocator26.telemetry

import io.github.mtnmanak.rocketlocator26.core.flight.RocketState
import io.github.mtnmanak.rocketlocator26.core.flight.fixAgeMs
import io.github.mtnmanak.rocketlocator26.core.flight.isFixStale
import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import io.github.mtnmanak.rocketlocator26.core.sim.SimulatedSample
import io.github.mtnmanak.rocketlocator26.transport.NmeaSource
import io.github.mtnmanak.rocketlocator26.transport.SimulatorSource
import io.github.mtnmanak.rocketlocator26.transport.SourceEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryRepositoryTest {
    @Test
    fun reconnectWithoutReplacementFixPreservesLastKnownPosition() =
        runTest {
            val repository = TelemetryRepository(backgroundScope) { EPOCH_MS + testScheduler.currentTime }
            repository.connect(
                source("receiver") {
                    emit(SourceEvent.Connected)
                    emit(SourceEvent.LineReceived(validGga, 0L))
                    awaitCancellation()
                },
            )
            runCurrent()
            val before = repository.uiState.value.rocket
            assertEquals(GeoPoint(40.0, -105.0), before.position)
            assertNotNull(before.lastFixAtMs)
            assertFalse(before.isFixStale(EPOCH_MS))

            repository.disconnect()
            assertEquals(
                before.position,
                repository.uiState.value.rocket.position,
                "Disconnect by itself should retain the recovery position",
            )
            repository.connect(
                source("receiver") {
                    emit(SourceEvent.SourceError("receiver unavailable", willRetry = true))
                    emit(SourceEvent.Disconnected)
                    awaitCancellation()
                },
            )
            runCurrent()
            assertTrue(repository.uiState.value.awaitingFix)
            advanceTimeBy(10_000L)
            runCurrent()
            val after = repository.uiState.value
            // This also guards the terminal-only DISCONNECTED contract while retrying.
            assertEquals(ConnectionState.CONNECTING, after.connectionState)
            assertAll(
                {
                    assertEquals(
                        before.position,
                        after.rocket.position,
                        "Connecting to an unavailable receiver must retain the last known position",
                    )
                },
                { assertEquals(before.lastFixAtMs, after.rocket.lastFixAtMs) },
                { assertEquals(before, after.rocket, "Connect must retain path, altitude maxima and baseline data") },
                { assertTrue(after.rocket.isFixStale(EPOCH_MS + testScheduler.currentTime)) },
            )
        }

    @ParameterizedTest(name = "300 seconds of {0}")
    @ValueSource(strings = ["garbage", "unsupported", "void-rmc", "mixed"])
    fun publishedFixTimestampExposesGrowingAge(traffic: String) =
        runTest {
            val repository = TelemetryRepository(backgroundScope) { EPOCH_MS + testScheduler.currentTime }
            val nonFixLines =
                when (traffic) {
                    "garbage" -> {
                        listOf("not an NMEA sentence")
                    }

                    "unsupported" -> {
                        listOf(checked("GPZDA,120003,31,10,2026,00,00"))
                    }

                    "void-rmc" -> {
                        listOf(checked("GPRMC,120003,V,4100.000,N,10600.000,W,0.0,0.0,311026,,,N"))
                    }

                    else -> {
                        listOf(
                            "not an NMEA sentence",
                            checked("GPZDA,120003,31,10,2026,00,00"),
                            checked("GPRMC,120003,V,4100.000,N,10600.000,W,0.0,0.0,311026,,,N"),
                        )
                    }
                }
            repository.connect(
                source("receiver") {
                    emit(SourceEvent.Connected)
                    emit(SourceEvent.LineReceived(validGga, 0L))
                    repeat(300) { index ->
                        delay(1_000L)
                        emit(SourceEvent.LineReceived(nonFixLines[index % nonFixLines.size], (index + 1L) * 1_000L))
                    }
                    awaitCancellation() // Keep the link alive; normal completion would disconnect.
                },
            )
            runCurrent()
            val before = repository.uiState.value.rocket
            assertEquals(GeoPoint(40.0, -105.0), before.position)
            val firstFixAt = requireNotNull(before.lastFixAtMs)
            val virtualStart = testScheduler.currentTime

            advanceTimeBy(300_000L)
            runCurrent()
            val after = repository.uiState.value
            // The injected wall clock and coroutine delays share the virtual scheduler.
            val consumerNow = firstFixAt + testScheduler.currentTime - virtualStart
            val ageMs = consumerNow - requireNotNull(after.rocket.lastFixAtMs)
            assertAll(
                { assertEquals(ConnectionState.CONNECTED, after.connectionState) },
                { assertEquals(before.position, after.rocket.position) },
                {
                    assertEquals(
                        firstFixAt,
                        after.rocket.lastFixAtMs,
                        "Non-fix traffic must not renew the fix timestamp",
                    )
                },
                {
                    assertEquals(
                        300_000L,
                        ageMs,
                        "A consumer clock must be able to distinguish this fix from a current fix",
                    )
                },
                { assertEquals(300_000L, after.rocket.fixAgeMs(consumerNow)) },
                { assertTrue(after.rocket.isFixStale(consumerNow)) },
            )
        }

    @Test
    fun newFlightClearsFlightDataAndBaselineWithoutDisconnecting() =
        runTest {
            val events = Channel<SourceEvent>(Channel.UNLIMITED)
            val repository = TelemetryRepository(backgroundScope) { EPOCH_MS + testScheduler.currentTime }
            repository.connect(channelSource(events))
            events.send(SourceEvent.Connected)
            events.send(SourceEvent.LineReceived(validGga, 0L))
            runCurrent()
            advanceTimeBy(1_000L)
            events.send(SourceEvent.LineReceived(checked(ggaBody.replace("1500.0", "1600.0")), 1_000L))
            runCurrent()
            assertEquals(100.0, repository.uiState.value.rocket.maxAltitudeAglMeters)
            assertFalse(
                repository.uiState.value.rocket.path
                    .isEmpty(),
            )

            repository.newFlight()
            assertEquals(RocketState(), repository.uiState.value.rocket)
            assertFalse(repository.uiState.value.positionIsSimulator)
            assertFalse(repository.uiState.value.awaitingFix)
            assertEquals(ConnectionState.CONNECTED, repository.uiState.value.connectionState)
            events.send(SourceEvent.LineReceived(checked(ggaBody.replace("1500.0", "1700.0")), 1_000L))
            runCurrent()
            assertEquals(0.0, repository.uiState.value.rocket.altitudeAglMeters)
            assertEquals(1700.0, repository.uiState.value.rocket.maxAltitudeMslMeters)
        }

    @Test
    fun reconnectResetsChecksumLatchAndOnlyNewFixMakesHeldPositionCurrent() =
        runTest {
            val repository = TelemetryRepository(backgroundScope) { EPOCH_MS + testScheduler.currentTime }
            repository.connect(
                source("first") {
                    emit(SourceEvent.LineReceived(validGga, 0L))
                    awaitCancellation()
                },
            )
            runCurrent()
            val events = Channel<SourceEvent>(Channel.UNLIMITED)
            repository.connect(channelSource(events))
            events.send(SourceEvent.Connected)
            events.send(SourceEvent.LineReceived(checked(ggaBody.replace(",W,1,", ",W,0,")), 0L))
            runCurrent()
            assertTrue(repository.uiState.value.awaitingFix)
            assertEquals(GeoPoint(40.0, -105.0), repository.uiState.value.rocket.position)

            // A third, legacy receiver must be allowed to omit checksums again.
            repository.connect(
                source("legacy") {
                    emit(SourceEvent.Connected)
                    emit(SourceEvent.LineReceived("\$" + ggaBody.replace("4000.000", "4100.000"), 0L))
                    awaitCancellation()
                },
            )
            runCurrent()
            assertFalse(repository.uiState.value.awaitingFix)
            assertEquals(GeoPoint(41.0, -105.0), repository.uiState.value.rocket.position)
        }

    @Test
    fun retryBackoffIsConnectingAndFatalDisconnectAndCompletionAreTerminal() =
        runTest {
            val events = Channel<SourceEvent>(Channel.UNLIMITED)
            val repository = TelemetryRepository(backgroundScope)
            repository.connect(channelSource(events))
            events.send(SourceEvent.Connected)
            runCurrent()
            assertEquals(ConnectionState.CONNECTED, repository.uiState.value.connectionState)
            events.send(SourceEvent.SourceError("retry", willRetry = true))
            events.send(SourceEvent.Disconnected)
            runCurrent()
            assertEquals(ConnectionState.CONNECTING, repository.uiState.value.connectionState)
            events.send(SourceEvent.Connected)
            runCurrent()
            assertEquals(ConnectionState.CONNECTED, repository.uiState.value.connectionState)
            events.send(SourceEvent.SourceError("fatal", willRetry = false))
            events.send(SourceEvent.Disconnected)
            runCurrent()
            assertEquals(ConnectionState.DISCONNECTED, repository.uiState.value.connectionState)

            repository.connect(source("completed") { emit(SourceEvent.Connected) })
            runCurrent()
            assertEquals(ConnectionState.DISCONNECTED, repository.uiState.value.connectionState)
            repository.connect(
                source("retrying") {
                    emit(SourceEvent.SourceError("retry", willRetry = true))
                    awaitCancellation()
                },
            )
            runCurrent()
            repository.disconnect()
            assertEquals(ConnectionState.DISCONNECTED, repository.uiState.value.connectionState)
        }

    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun supersededSessionEventsCannotOverwriteNewSession() =
        runTest {
            val repository = TelemetryRepository(backgroundScope)
            // Deliberately non-cooperative teardown exercises the session guard,
            // rather than relying on flow's normal cancellation checks to drop data.
            val oldSource =
                object : NmeaSource {
                    override val name = "old receiver"

                    override fun events(): Flow<SourceEvent> =
                        object : Flow<SourceEvent> {
                            override suspend fun collect(collector: FlowCollector<SourceEvent>) {
                                collector.emit(SourceEvent.Connected)
                                try {
                                    awaitCancellation()
                                } finally {
                                    withContext(NonCancellable) {
                                        collector.emit(SourceEvent.LineReceived(validGga, 0L))
                                        collector.emit(SourceEvent.SourceError("old error", willRetry = false))
                                        collector.emit(SourceEvent.Disconnected)
                                        collector.emit(SourceEvent.Connected)
                                    }
                                }
                            }
                        }
                }
            repository.connect(oldSource)
            runCurrent()
            repository.connect(source("new receiver") { awaitCancellation() })
            runCurrent()
            assertEquals("new receiver", repository.uiState.value.sourceName)
            assertEquals(ConnectionState.CONNECTING, repository.uiState.value.connectionState)
            assertEquals(RocketState(), repository.uiState.value.rocket)
            assertTrue(
                repository.uiState.value.recentLines
                    .isEmpty(),
            )
        }

    @Test
    fun simulatorOriginFollowsHeldFixInsteadOfConnectionNameOrAttempt() =
        runTest {
            val repository = TelemetryRepository(backgroundScope)
            assertFalse(repository.uiState.value.needsSimulatorConfirmation())
            repository.connect(
                source("Simulator") {
                    emit(SourceEvent.LineReceived(validGga, 0L))
                    awaitCancellation()
                },
            )
            runCurrent()
            assertFalse(repository.uiState.value.positionIsSimulator)
            assertTrue(repository.uiState.value.needsSimulatorConfirmation())

            repository.connect(SimulatorSource(samplesProvider = { emptySequence() }))
            runCurrent()
            assertFalse(repository.uiState.value.positionIsSimulator)
            assertTrue(repository.uiState.value.needsSimulatorConfirmation(), "No demo fix replaced the real position")

            repository.newFlight()
            repository.connect(SimulatorSource(samplesProvider = { sequenceOf(SimulatedSample(0L, listOf(validGga))) }))
            runCurrent()
            assertTrue(repository.uiState.value.positionIsSimulator)
            assertFalse(repository.uiState.value.needsSimulatorConfirmation())
            repository.disconnect()
            assertTrue(repository.uiState.value.positionIsSimulator)
            repository.connect(source("real receiver") { awaitCancellation() })
            runCurrent()
            assertTrue(repository.uiState.value.positionIsSimulator, "Connecting alone cannot relabel a held demo fix")
            assertFalse(repository.uiState.value.needsSimulatorConfirmation())
            repository.newFlight()
            assertFalse(repository.uiState.value.positionIsSimulator)
            assertFalse(repository.uiState.value.needsSimulatorConfirmation())
        }

    @Test
    fun identicalSameMillisecondRealFixReplacesSimulatorOrigin() =
        runTest {
            val repository = TelemetryRepository(backgroundScope) { EPOCH_MS }
            repository.connect(SimulatorSource(samplesProvider = { sequenceOf(SimulatedSample(0L, listOf(validGga))) }))
            runCurrent()
            assertTrue(repository.uiState.value.positionIsSimulator)
            val events = Channel<SourceEvent>(Channel.UNLIMITED)
            repository.connect(channelSource(events))
            events.send(SourceEvent.LineReceived(checked(ggaBody.replace(",W,1,", ",W,6,")), 0L))
            runCurrent()
            assertTrue(repository.uiState.value.positionIsSimulator, "A rejected fix cannot change the held position's origin")
            assertTrue(repository.uiState.value.awaitingFix)
            events.send(SourceEvent.LineReceived(validGga, 0L))
            runCurrent()
            assertFalse(repository.uiState.value.positionIsSimulator)
            assertFalse(repository.uiState.value.awaitingFix)
            assertTrue(repository.uiState.value.needsSimulatorConfirmation())
            assertEquals(EPOCH_MS, repository.uiState.value.rocket.lastFixAtMs)
        }

    @Test
    fun reconnectKeepsAltitudeBaselineAndMaximumWhenNewFixArrives() =
        runTest {
            val repository = TelemetryRepository(backgroundScope) { EPOCH_MS + testScheduler.currentTime }
            repository.connect(
                source("first") {
                    emit(SourceEvent.LineReceived(validGga, 0L))
                    emit(SourceEvent.LineReceived(checked(ggaBody.replace("1500.0", "1600.0")), 0L))
                    awaitCancellation()
                },
            )
            runCurrent()
            advanceTimeBy(10_000L)
            repository.connect(
                source("second") {
                    emit(SourceEvent.LineReceived(checked(ggaBody.replace("1500.0", "1550.0")), 0L))
                    awaitCancellation()
                },
            )
            runCurrent()
            assertEquals(50.0, repository.uiState.value.rocket.altitudeAglMeters)
            assertEquals(100.0, repository.uiState.value.rocket.maxAltitudeAglMeters)
            assertEquals(1600.0, repository.uiState.value.rocket.maxAltitudeMslMeters)
            assertFalse(repository.uiState.value.awaitingFix)
        }

    private fun channelSource(channel: Channel<SourceEvent>) =
        object : NmeaSource {
            override val name = "receiver"

            override fun events(): Flow<SourceEvent> = channel.receiveAsFlow()
        }

    private fun source(
        name: String,
        events: suspend kotlinx.coroutines.flow.FlowCollector<SourceEvent>.() -> Unit,
    ) = object : NmeaSource {
        override val name: String = name

        override fun events(): Flow<SourceEvent> = flow(events)
    }

    private fun checked(body: String): String =
        "\$$body*" +
            body
                .fold(0) { sum, char -> sum xor char.code }
                .toString(16)
                .uppercase()
                .padStart(2, '0')

    private val ggaBody = "GPGGA,120000,4000.000,N,10500.000,W,1,08,0.9,1500.0,M,0.0,M,,"
    private val validGga = checked(ggaBody)

    private companion object {
        const val EPOCH_MS = 1_000_000L
    }
}

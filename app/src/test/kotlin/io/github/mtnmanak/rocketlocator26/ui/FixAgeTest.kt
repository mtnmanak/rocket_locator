package io.github.mtnmanak.rocketlocator26.ui

import io.github.mtnmanak.rocketlocator26.core.flight.RocketState
import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class FixAgeTest {
    @ParameterizedTest
    @CsvSource("0,0 s", "59000,59 s", "60000,1 min", "3660000,1 h")
    fun ageUsesWholeSecondsMinutesAndHours(
        ageMs: Long,
        expected: String,
    ) {
        assertEquals(expected, formatFixAge(ageMs, "%1\$d s", "%1\$d min", "%1\$d h", Locale.US))
    }

    @Test
    fun ageAcceptsLocalizedTemplatesAndClampsBackwardTime() {
        assertEquals("0 seconds", formatFixAge(-1L, "%1\$d seconds", "%1\$d minutes", "%1\$d hours", Locale.US))
        assertEquals("2 minutes", formatFixAge(120_000L, "%1\$d seconds", "%1\$d minutes", "%1\$d hours", Locale.US))
    }

    @Test
    fun silenceAloneMakesPositionStaleAtTenSecondsAndNewFixRestoresLiveState() {
        val rocket = RocketState(position = GeoPoint(40.0, -105.0), lastFixAtMs = 1_000L)
        assertFalse(isPositionStale(rocket, awaitingFix = false, nowMs = 10_999L))
        assertTrue(isPositionStale(rocket, awaitingFix = false, nowMs = 11_000L))
        assertTrue(isPositionStale(rocket, awaitingFix = false, nowMs = 301_000L))
        assertFalse(isPositionStale(rocket.copy(lastFixAtMs = 301_000L), awaitingFix = false, nowMs = 301_000L))
    }

    @Test
    fun retainedOrRestoredPositionIsLastKnownButEmptyFlightIsNotStale() {
        val rocket = RocketState(position = GeoPoint(40.0, -105.0), lastFixAtMs = 1_000L)
        assertTrue(isPositionStale(rocket, awaitingFix = true, nowMs = 1_001L))
        assertTrue(isPositionStale(rocket.copy(lastFixAtMs = null), awaitingFix = false, nowMs = 1_001L))
        assertFalse(isPositionStale(RocketState(), awaitingFix = false, nowMs = 301_000L))
    }

    @Test
    fun tickerAdvancesWithoutTelemetryAndStopsOnCancellation() =
        runTest {
            val ticks = mutableListOf<Long>()
            val job =
                backgroundScope.launch {
                    fixClockTicks(enabled = true) { testScheduler.currentTime }.collect { ticks += it }
                }
            runCurrent()
            assertEquals(listOf(0L), ticks)
            advanceTimeBy(999L)
            runCurrent()
            assertEquals(listOf(0L), ticks)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(0L, 1_000L), ticks)
            advanceTimeBy(9_000L)
            runCurrent()
            val rocket = RocketState(position = GeoPoint(40.0, -105.0), lastFixAtMs = 0L)
            assertTrue(isPositionStale(rocket, awaitingFix = false, nowMs = ticks.last()))
            job.cancel()
            runCurrent()
            val count = ticks.size
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(count, ticks.size)
        }

    @Test
    fun emptyFlightDoesNotKeepTicking() =
        runTest {
            val ticks = mutableListOf<Long>()
            fixClockTicks(enabled = false) { testScheduler.currentTime }.collect { ticks += it }
            assertEquals(listOf(0L), ticks)
        }
}

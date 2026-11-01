package io.github.mtnmanak.rocketlocator26.core.flight

import io.github.mtnmanak.rocketlocator26.core.geo.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RocketStateTest {
    @ParameterizedTest
    @ValueSource(longs = [9999L, 10000L, 10001L])
    fun fixAgeAndStalenessUseTenSecondBoundary(ageMs: Long) {
        val state = RocketState(position = GeoPoint(40.0, -105.0), lastFixAtMs = 1_000L)
        assertEquals(10_000L, STALE_AFTER_MS)
        assertEquals(ageMs, state.fixAgeMs(1_000L + ageMs))
        assertEquals(ageMs >= 10_000L, state.isFixStale(1_000L + ageMs))
    }

    @Test
    fun sentenceArrivalDoesNotRenewFixAge() {
        val state = RocketState(lastFixAtMs = 1_000L, lastSentenceAtMs = 10_999L)
        assertEquals(10_000L, state.fixAgeMs(11_000L))
        assertTrue(state.isFixStale(11_000L))
    }

    @Test
    fun noLiveFixHasNoAgeAndIsNotStale() {
        for (state in listOf(RocketState(), RocketState(position = GeoPoint(40.0, -105.0)))) {
            assertNull(state.fixAgeMs(100_000L))
            assertFalse(state.isFixStale(100_000L))
        }
    }

    @Test
    fun futureFixTimeClampsAgeToZero() {
        val state = RocketState(lastFixAtMs = 1_000L)
        assertEquals(0L, state.fixAgeMs(999L))
        assertEquals(0L, state.fixAgeMs(1_000L))
        assertFalse(state.isFixStale(999L))
    }

    @Test
    fun callerCanOverrideStalenessThreshold() {
        val state = RocketState(lastFixAtMs = 1_000L)
        assertFalse(state.isFixStale(1_499L, staleAfterMs = 500L))
        assertTrue(state.isFixStale(1_500L, staleAfterMs = 500L))
    }

    @Test
    fun extremeTimesDoNotOverflowToNegativeAge() {
        val state = RocketState(lastFixAtMs = Long.MIN_VALUE)
        assertEquals(Long.MAX_VALUE, state.fixAgeMs(Long.MAX_VALUE))
        assertTrue(state.isFixStale(Long.MAX_VALUE))
        assertEquals(0L, RocketState(lastFixAtMs = Long.MAX_VALUE).fixAgeMs(Long.MIN_VALUE))
    }
}

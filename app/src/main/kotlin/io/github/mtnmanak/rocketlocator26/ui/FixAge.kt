package io.github.mtnmanak.rocketlocator26.ui

import android.os.SystemClock
import io.github.mtnmanak.rocketlocator26.core.flight.RocketState
import io.github.mtnmanak.rocketlocator26.core.flight.isFixStale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/** The unit a fix age is shown in. */
enum class AgeUnit { SECONDS, MINUTES, HOURS }

/** Whole elapsed units: switch to minutes at 60 seconds and hours at 60 minutes. */
fun fixAgeParts(ageMs: Long): Pair<Long, AgeUnit> {
    val seconds = ageMs.coerceAtLeast(0L) / 1_000L
    return when {
        seconds < 60L -> seconds to AgeUnit.SECONDS
        seconds < 3_600L -> seconds / 60L to AgeUnit.MINUTES
        else -> seconds / 3_600L to AgeUnit.HOURS
    }
}

/** Short display form ("3 s", "2 min", "1 h"); spoken forms use plurals resources instead. */
fun formatFixAge(
    ageMs: Long,
    secondsFormat: String,
    minutesFormat: String,
    hoursFormat: String,
    locale: Locale = Locale.getDefault(),
): String {
    val (value, unit) = fixAgeParts(ageMs)
    val format =
        when (unit) {
            AgeUnit.SECONDS -> secondsFormat
            AgeUnit.MINUTES -> minutesFormat
            AgeUnit.HOURS -> hoursFormat
        }
    return String.format(locale, format, value)
}

/** Retained sessions and restored positions are last-known before another measured fix. */
internal fun isPositionStale(
    rocket: RocketState,
    awaitingFix: Boolean,
    nowMs: Long,
): Boolean = rocket.position != null && (awaitingFix || rocket.lastFixAtMs == null || rocket.isFixStale(nowMs))

/**
 * Receipt and display use the same MONOTONIC clock (`SystemClock.elapsedRealtime`, wired in `RocketLocatorApp`), so a
 * wall-clock correction can never make an old fix look fresh. Cancellation stops the one-second ticker.
 */
internal fun fixClockTicks(
    enabled: Boolean,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
): Flow<Long> =
    flow {
        emit(nowMs())
        while (enabled) {
            delay(1_000L)
            emit(nowMs())
        }
    }

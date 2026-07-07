package io.github.mtnmanak.rocketlocator26.transport

/**
 * Exponential-backoff schedule for reconnect attempts.
 *
 * Each call to [nextDelayMs] returns the delay to wait before the next attempt
 * and doubles the internal delay for the following call, capped at
 * [maxDelayMs]. With the defaults the sequence is
 * `1000, 2000, 4000, 8000, 15000, 15000, ...`.
 *
 * Call [reset] once a connection succeeds so the next failure starts backing
 * off from [initialDelayMs] again.
 *
 * Not thread-safe; use from a single supervision coroutine.
 *
 * @param initialDelayMs delay before the first retry, in milliseconds.
 * @param maxDelayMs ceiling the doubling delay saturates at, in milliseconds.
 */
class ReconnectPolicy(
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 15_000,
) {

    init {
        require(initialDelayMs > 0) { "initialDelayMs must be positive, was $initialDelayMs" }
        require(maxDelayMs >= initialDelayMs) {
            "maxDelayMs ($maxDelayMs) must be >= initialDelayMs ($initialDelayMs)"
        }
    }

    private var nextDelayMs = initialDelayMs

    /** Returns the delay to wait before the next attempt, then doubles it (capped). */
    fun nextDelayMs(): Long {
        val delayMs = nextDelayMs
        nextDelayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
        return delayMs
    }

    /** Restarts the schedule from [initialDelayMs] (call after a successful connect). */
    fun reset() {
        nextDelayMs = initialDelayMs
    }
}

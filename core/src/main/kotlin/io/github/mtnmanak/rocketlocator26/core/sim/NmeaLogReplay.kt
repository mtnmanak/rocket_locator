package io.github.mtnmanak.rocketlocator26.core.sim

/**
 * Replays a recorded NMEA log as a simulated receiver.
 *
 * Each non-blank line of [lines] becomes one [SimulatedSample] carrying that
 * single line, paced at a fixed cadence of [intervalMs] between samples
 * (the first sample is at offset 0). Lines are trimmed of surrounding
 * whitespace and CR/LF; blank lines are skipped entirely and do not consume a
 * time slot. No validation is performed here — garbage lines are passed
 * through so the downstream parser's tolerance is exercised exactly as it
 * would be with live hardware.
 *
 * Fully deterministic: no randomness and no clock access.
 *
 * @property lines raw log lines, in playback order.
 * @property intervalMs milliseconds between consecutive samples; must be positive.
 */
class NmeaLogReplay(private val lines: List<String>, private val intervalMs: Long = 1000) {

    init {
        require(intervalMs > 0) { "intervalMs must be > 0, was $intervalMs" }
    }

    /**
     * The replay as a finite, replayable sequence: one trimmed non-blank line
     * per sample, at time offsets 0, [intervalMs], 2 * [intervalMs], ...
     */
    fun samples(): Sequence<SimulatedSample> =
        lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, line -> SimulatedSample(index * intervalMs, listOf(line)) }
}

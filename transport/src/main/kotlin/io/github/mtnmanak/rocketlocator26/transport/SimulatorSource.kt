package io.github.mtnmanak.rocketlocator26.transport

import io.github.mtnmanak.rocketlocator26.core.sim.SimulatedSample
import io.github.mtnmanak.rocketlocator26.core.sim.SyntheticFlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * An [NmeaSource] that replays a [SyntheticFlight] in (optionally scaled) real time.
 *
 * Collection of [events] emits [SourceEvent.Connected], then walks the flight's
 * samples in order: for each sample it suspends until the sample's
 * `timeOffsetMs / timeScale` mark (relative to the start of collection) and emits
 * one [SourceEvent.LineReceived] per sentence, with [SourceEvent.LineReceived.timestampMs]
 * set to that scaled offset. After the last sample it emits
 * [SourceEvent.Disconnected] and completes.
 *
 * The flow is cold: every collection replays the flight from the beginning, and
 * cancelling the collector stops playback immediately with no leaked work.
 * Pacing uses a monotonic clock and [delay]. Tests inject the matching
 * `kotlinx-coroutines-test` virtual clock through the internal constructor.
 *
 * @param samplesProvider supplies a fresh sample sequence per collection (cold-flow
 *   semantics require re-iterability).
 * @param timeScale playback speed multiplier: `1.0` is real time, `2.0` is twice
 *   real speed, `0.5` is half speed. Must be positive and finite.
 * @param nowMs monotonic milliseconds; tests supply their scheduler's clock so
 *   collector suspensions and playback delays share one time source.
 */
class SimulatorSource internal constructor(
    private val samplesProvider: () -> Sequence<SimulatedSample>,
    private val timeScale: Double,
    private val nowMs: () -> Long,
) : NmeaSource {
    constructor(samplesProvider: () -> Sequence<SimulatedSample>, timeScale: Double = 1.0) :
        this(samplesProvider, timeScale, { System.nanoTime() / 1_000_000 })

    constructor(flight: SyntheticFlight, timeScale: Double = 1.0) : this(flight::samples, timeScale)

    init {
        require(timeScale > 0.0 && timeScale.isFinite()) {
            "timeScale must be a positive finite number, was $timeScale"
        }
    }

    override val name: String = "Simulator"

    override fun events(): Flow<SourceEvent> =
        flow {
            val startMs = nowMs()
            emit(SourceEvent.Connected)
            for (sample in samplesProvider()) {
                val targetMs = (sample.timeOffsetMs / timeScale).toLong()
                // Include collector work in elapsed time. Late samples never wait.
                val elapsedMs = nowMs() - startMs
                delay(if (targetMs > elapsedMs) targetMs - elapsedMs else 0)
                for (sentence in sample.sentences) {
                    emit(SourceEvent.LineReceived(raw = sentence, timestampMs = targetMs))
                }
            }
            emit(SourceEvent.Disconnected)
        }
}

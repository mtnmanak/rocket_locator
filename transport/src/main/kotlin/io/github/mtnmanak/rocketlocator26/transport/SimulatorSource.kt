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
 * Pacing uses [delay], so tests can drive playback deterministically with
 * `kotlinx-coroutines-test` virtual time.
 *
 * @param samplesProvider supplies a fresh sample sequence per collection (cold-flow
 *   semantics require re-iterability).
 * @param timeScale playback speed multiplier: `1.0` is real time, `2.0` is twice
 *   real speed, `0.5` is half speed. Must be positive and finite.
 */
class SimulatorSource(
    private val samplesProvider: () -> Sequence<SimulatedSample>,
    private val timeScale: Double = 1.0,
) : NmeaSource {

    constructor(flight: SyntheticFlight, timeScale: Double = 1.0) : this(flight::samples, timeScale)

    init {
        require(timeScale > 0.0 && timeScale.isFinite()) {
            "timeScale must be a positive finite number, was $timeScale"
        }
    }

    override val name: String = "Simulator"

    override fun events(): Flow<SourceEvent> = flow {
        emit(SourceEvent.Connected)
        // Milliseconds of playback time already spent waiting. Accumulating our own
        // clock (rather than reading a wall clock) keeps pacing exact under
        // virtual-time test dispatchers and immune to collector back-pressure drift.
        var elapsedMs = 0L
        for (sample in samplesProvider()) {
            val targetMs = (sample.timeOffsetMs / timeScale).toLong()
            val waitMs = targetMs - elapsedMs
            if (waitMs > 0) {
                delay(waitMs)
                elapsedMs = targetMs
            }
            for (sentence in sample.sentences) {
                emit(SourceEvent.LineReceived(raw = sentence, timestampMs = targetMs))
            }
        }
        emit(SourceEvent.Disconnected)
    }
}

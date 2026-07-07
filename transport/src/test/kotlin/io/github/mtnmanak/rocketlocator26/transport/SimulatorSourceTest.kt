package io.github.mtnmanak.rocketlocator26.transport

import io.github.mtnmanak.rocketlocator26.core.sim.SimulatedSample
import io.github.mtnmanak.rocketlocator26.core.sim.SyntheticFlight
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimulatorSourceTest {

    // Known-good canonical NMEA fixtures (checksums verified).
    private val gga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    private val rmc = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"

    private fun flightOf(vararg samples: SimulatedSample): () -> Sequence<SimulatedSample> =
        { samples.asSequence() }

    // ---------------------------------------------------------------- ordering

    @Test
    fun `emits Connected first and Disconnected last`() = runTest {
        val flight = flightOf(
            SimulatedSample(timeOffsetMs = 0, sentences = listOf(gga)),
            SimulatedSample(timeOffsetMs = 1000, sentences = listOf(rmc)),
        )

        val events = SimulatorSource(flight).events().toList()

        assertEquals(SourceEvent.Connected, events.first())
        assertEquals(SourceEvent.Disconnected, events.last())
        assertEquals(1, events.count { it is SourceEvent.Connected })
        assertEquals(1, events.count { it is SourceEvent.Disconnected })
    }

    @Test
    fun `delivers every sentence in sample and sentence order`() = runTest {
        val flight = flightOf(
            SimulatedSample(timeOffsetMs = 0, sentences = listOf(gga, rmc)),
            SimulatedSample(timeOffsetMs = 1000, sentences = listOf(gga)),
            SimulatedSample(timeOffsetMs = 2000, sentences = listOf(rmc, gga)),
        )

        val lines = SimulatorSource(flight).events()
            .toList()
            .filterIsInstance<SourceEvent.LineReceived>()

        assertEquals(listOf(gga, rmc, gga, rmc, gga), lines.map { it.raw })
        assertEquals(listOf(0L, 0L, 1000L, 2000L, 2000L), lines.map { it.timestampMs })
    }

    @Test
    fun `empty flight emits Connected then Disconnected with no delay`() = runTest {
        val events = SimulatorSource(flightOf()).events().toList()

        assertEquals(listOf<SourceEvent>(SourceEvent.Connected, SourceEvent.Disconnected), events)
        assertEquals(0L, currentTime)
    }

    // ------------------------------------------------------------------ pacing

    @Test
    fun `pacing at real speed matches timeOffsetMs in virtual time`() = runTest {
        val flight = flightOf(
            SimulatedSample(timeOffsetMs = 0, sentences = listOf(gga)),
            SimulatedSample(timeOffsetMs = 1000, sentences = listOf(rmc)),
            SimulatedSample(timeOffsetMs = 3500, sentences = listOf(gga)),
        )

        val arrivals = mutableListOf<Pair<SourceEvent, Long>>()
        SimulatorSource(flight).events().collect { arrivals += it to currentTime }

        assertEquals(SourceEvent.Connected to 0L, arrivals[0])
        assertEquals(SourceEvent.LineReceived(gga, 0L) to 0L, arrivals[1])
        assertEquals(SourceEvent.LineReceived(rmc, 1000L) to 1000L, arrivals[2])
        assertEquals(SourceEvent.LineReceived(gga, 3500L) to 3500L, arrivals[3])
        assertEquals(SourceEvent.Disconnected to 3500L, arrivals[4])
        assertEquals(5, arrivals.size)
    }

    @Test
    fun `timeScale 10 plays back ten times faster`() = runTest {
        val flight = flightOf(
            SimulatedSample(timeOffsetMs = 1000, sentences = listOf(gga)),
            SimulatedSample(timeOffsetMs = 5000, sentences = listOf(rmc)),
        )

        val arrivals = mutableListOf<Pair<SourceEvent, Long>>()
        SimulatorSource(flight, timeScale = 10.0).events().collect { arrivals += it to currentTime }

        assertEquals(SourceEvent.LineReceived(gga, 100L) to 100L, arrivals[1])
        assertEquals(SourceEvent.LineReceived(rmc, 500L) to 500L, arrivals[2])
        assertEquals(500L, currentTime)
    }

    @Test
    fun `timeScale below one slows playback down`() = runTest {
        val flight = flightOf(SimulatedSample(timeOffsetMs = 1000, sentences = listOf(gga)))

        val arrivals = mutableListOf<Pair<SourceEvent, Long>>()
        SimulatorSource(flight, timeScale = 0.5).events().collect { arrivals += it to currentTime }

        assertEquals(SourceEvent.LineReceived(gga, 2000L) to 2000L, arrivals[1])
        assertEquals(2000L, currentTime)
    }

    @Test
    fun `fractional scaled offsets truncate toward zero`() = runTest {
        val flight = flightOf(SimulatedSample(timeOffsetMs = 1000, sentences = listOf(gga)))

        val lines = SimulatorSource(flight, timeScale = 3.0).events()
            .toList()
            .filterIsInstance<SourceEvent.LineReceived>()

        // 1000 / 3.0 = 333.33... -> 333 ms
        assertEquals(333L, lines.single().timestampMs)
        assertEquals(333L, currentTime)
    }

    @Test
    fun `samples at the same offset emit together without extra delay`() = runTest {
        val flight = flightOf(
            SimulatedSample(timeOffsetMs = 1000, sentences = listOf(gga)),
            SimulatedSample(timeOffsetMs = 1000, sentences = listOf(rmc)),
        )

        val arrivals = mutableListOf<Pair<SourceEvent, Long>>()
        SimulatorSource(flight).events().collect { arrivals += it to currentTime }

        assertEquals(SourceEvent.LineReceived(gga, 1000L) to 1000L, arrivals[1])
        assertEquals(SourceEvent.LineReceived(rmc, 1000L) to 1000L, arrivals[2])
        assertEquals(1000L, currentTime)
    }

    @Test
    fun `out-of-order offsets never delay backwards and still deliver everything`() = runTest {
        val flight = flightOf(
            SimulatedSample(timeOffsetMs = 2000, sentences = listOf(gga)),
            SimulatedSample(timeOffsetMs = 1000, sentences = listOf(rmc)),
        )

        val events = SimulatorSource(flight).events().toList()

        assertEquals(
            listOf(gga, rmc),
            events.filterIsInstance<SourceEvent.LineReceived>().map { it.raw },
        )
        assertEquals(SourceEvent.Disconnected, events.last())
        // The earlier-offset sample must not add time on top of the 2000 ms mark.
        assertEquals(2000L, currentTime)
    }

    // ------------------------------------------------------------- cancellation

    @Test
    fun `cancellation mid-flight stops cleanly without Disconnected`() = runTest {
        val flight = flightOf(
            SimulatedSample(timeOffsetMs = 1000, sentences = listOf(gga)),
            SimulatedSample(timeOffsetMs = 10_000, sentences = listOf(rmc)),
        )
        val events = mutableListOf<SourceEvent>()
        val job = launch { SimulatorSource(flight).events().collect { events += it } }

        advanceTimeBy(5000) // past the first sample, well before the second
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(
            listOf(SourceEvent.Connected, SourceEvent.LineReceived(gga, 1000L)),
            events,
        )
        assertFalse(events.any { it is SourceEvent.Disconnected })

        // No leaked work: advancing time further produces nothing new.
        advanceTimeBy(60_000)
        assertEquals(2, events.size)
    }

    // ------------------------------------------------------------------ misc

    @Test
    fun `flow is cold - each collection replays from the start`() = runTest {
        val flight = flightOf(
            SimulatedSample(timeOffsetMs = 500, sentences = listOf(gga)),
            SimulatedSample(timeOffsetMs = 1500, sentences = listOf(rmc)),
        )
        val source = SimulatorSource(flight)

        val first = source.events().toList()
        val timeAfterFirst = currentTime
        val second = source.events().toList()

        assertEquals(first, second)
        // The second collection re-paced the whole flight (another 1500 ms).
        assertEquals(timeAfterFirst + 1500L, currentTime)
    }

    @Test
    fun `rejects non-positive or non-finite timeScale`() {
        val flight = flightOf(SimulatedSample(timeOffsetMs = 0, sentences = listOf(gga)))

        assertThrows(IllegalArgumentException::class.java) { SimulatorSource(flight, timeScale = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { SimulatorSource(flight, timeScale = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { SimulatorSource(flight, timeScale = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            SimulatorSource(flight, timeScale = Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `name is Simulator`() {
        assertEquals("Simulator", SimulatorSource(flightOf()).name)
    }
}

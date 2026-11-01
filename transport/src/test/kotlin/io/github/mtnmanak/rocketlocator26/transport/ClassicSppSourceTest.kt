package io.github.mtnmanak.rocketlocator26.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ClassicSppSourceTest {
    private val address = "00:14:03:05:59:02"

    private fun TestScope.source(factory: SppLinkFactory): ClassicSppSource =
        ClassicSppSource(
            deviceAddress = address,
            deviceName = null,
            linkFactory = factory,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowMs = { currentTime },
        )

    @Test
    fun `lines retain chunk order and reception time`() =
        runTest {
            val link = FakeSppLink()
            val events = mutableListOf<SourceEvent>()
            val job = backgroundScope.launch { source { link }.events().collect { events += it } }
            runCurrent()
            link.reads.send(Read.Bytes(byteArrayOf())) // A zero-byte read is ignored.
            link.reads.send(Read.Bytes("first\r\nsec".toByteArray()))
            runCurrent()
            advanceTimeBy(500)
            link.reads.send(Read.Bytes("ond\nthird\r\n".toByteArray()))
            runCurrent()

            assertEquals(
                listOf(
                    SourceEvent.Connected,
                    SourceEvent.LineReceived("first", 0),
                    SourceEvent.LineReceived("second", 500),
                    SourceEvent.LineReceived("third", 500),
                ),
                events,
            )
            assertEquals(listOf("connect", "input"), link.calls)
            assertEquals(listOf(1024, 1024, 1024, 1024), link.bufferSizes)
            job.cancelAndJoin()
            assertEquals(1, link.closeCount)
        }

    @Test
    fun `watchdog emits retrying error then Disconnected at seven seconds`() =
        runTest {
            val link = FakeSppLink()
            val events = mutableListOf<SourceEvent>()
            var attempts = 0
            val job =
                backgroundScope.launch {
                    source {
                        attempts++
                        link
                    }.events().collect { events += it }
                }
            runCurrent()
            advanceTimeBy(6999)
            runCurrent()
            assertEquals(listOf(SourceEvent.Connected), events)
            advanceTimeBy(1)
            runCurrent()

            // The repository maps this willRetry=true / Disconnected pair to CONNECTING.
            assertEquals(
                listOf(
                    SourceEvent.Connected,
                    SourceEvent.SourceError("No data received for 7 s (watchdog)", willRetry = true),
                    SourceEvent.Disconnected,
                ),
                events,
            )
            assertEquals(1, link.closeCount)
            advanceTimeBy(999)
            runCurrent()
            assertEquals(1, attempts)
            job.cancelAndJoin()
        }

    @Test
    fun `partial bytes restart the watchdog without requiring a complete line`() =
        runTest {
            val link = FakeSppLink()
            val events = mutableListOf<SourceEvent>()
            val job = backgroundScope.launch { source { link }.events().collect { events += it } }
            runCurrent()
            advanceTimeBy(6000)
            link.reads.send(Read.Bytes("partial".toByteArray()))
            runCurrent()
            advanceTimeBy(6999)
            runCurrent()
            assertEquals(listOf(SourceEvent.Connected), events)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(SourceEvent.Disconnected, events.last())
            assertEquals(13_000L, currentTime)
            job.cancelAndJoin()
        }

    @Test
    fun `IO failures back off and successful reconnect resets the policy`() =
        runTest {
            val links = List(4) { FakeSppLink() }
            links[0].connectFailure = IOException("first connect")
            links[1].connectFailure = IOException("second connect")
            val attempts = mutableListOf<Long>()
            val events = mutableListOf<SourceEvent>()
            val job =
                backgroundScope.launch {
                    source {
                        val index = attempts.size
                        attempts += currentTime
                        links[index]
                    }.events().collect { events += it }
                }
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()
            advanceTimeBy(1999)
            runCurrent()
            assertEquals(listOf(0L, 1000L), attempts)
            advanceTimeBy(1)
            runCurrent()
            links[2].reads.send(Read.Bytes("before failure\n".toByteArray()))
            links[2].reads.send(Read.Fail(IOException("read failed")))
            runCurrent()
            advanceTimeBy(999)
            runCurrent()
            assertEquals(3, attempts.size)
            advanceTimeBy(1)
            runCurrent()

            assertEquals(listOf(0L, 1000L, 3000L, 4000L), attempts)
            assertEquals(
                listOf(
                    SourceEvent.SourceError("first connect", true),
                    SourceEvent.Disconnected,
                    SourceEvent.SourceError("second connect", true),
                    SourceEvent.Disconnected,
                    SourceEvent.Connected,
                    SourceEvent.LineReceived("before failure", 3000),
                    SourceEvent.SourceError("read failed", true),
                    SourceEvent.Disconnected,
                    SourceEvent.Connected,
                ),
                events,
            )
            assertTrue(links.take(3).all { it.closeCount == 1 })
            job.cancelAndJoin()
            assertEquals(1, links.last().closeCount)
        }

    @Test
    fun `permission revoked during connect or read is terminal`() =
        runTest {
            for (duringRead in listOf(false, true)) {
                val link = FakeSppLink()
                if (!duringRead) link.connectFailure = SecurityException("revoked")
                val events = mutableListOf<SourceEvent>()
                var attempts = 0
                val job =
                    backgroundScope.launch {
                        source {
                            attempts++
                            link
                        }.events().collect { events += it }
                    }
                runCurrent()
                if (duringRead) {
                    link.reads.send(Read.Fail(SecurityException("revoked")))
                    runCurrent()
                }

                assertEquals(
                    (if (duringRead) listOf(SourceEvent.Connected) else emptyList()) +
                        listOf(
                            SourceEvent.SourceError(
                                "Bluetooth permission BLUETOOTH_CONNECT not granted: revoked",
                                false,
                            ),
                            SourceEvent.Disconnected,
                        ),
                    events,
                )
                assertTrue(job.isCompleted)
                assertEquals(1, link.closeCount)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, attempts)
            }
        }

    @Test
    fun `resolution permission failure and malformed address are terminal`() =
        runTest {
            val cases =
                listOf(
                    SecurityException("revoked") to "Bluetooth permission BLUETOOTH_CONNECT not granted: revoked",
                    IllegalArgumentException("bad MAC") to "Invalid Bluetooth address \"$address\"",
                )
            for ((failure, message) in cases) {
                val events = mutableListOf<SourceEvent>()
                var attempts = 0
                val job =
                    backgroundScope.launch {
                        source {
                            attempts++
                            throw failure
                        }.events().collect { events += it }
                    }
                runCurrent()
                assertEquals(listOf(SourceEvent.SourceError(message, false), SourceEvent.Disconnected), events)
                assertTrue(job.isCompleted)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, attempts)
            }
        }

    @Test
    fun `missing or disabled adapter retries without reporting Connected`() =
        runTest {
            for (message in listOf("Bluetooth is not available on this device", "Bluetooth is turned off")) {
                val events = mutableListOf<SourceEvent>()
                var attempts = 0
                val job =
                    backgroundScope.launch {
                        source {
                            attempts++
                            throw IOException(message)
                        }.events().collect { events += it }
                    }
                runCurrent()
                assertEquals(listOf(SourceEvent.SourceError(message, true), SourceEvent.Disconnected), events)
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(2, attempts)
                assertEquals(events.take(2), events.drop(2))
                job.cancelAndJoin()
            }
        }

    @Test
    fun `unbonded device connection failure follows the ordinary IO retry path`() =
        runTest {
            // There is deliberately no bond-state precheck: the framework connect result decides.
            val link = FakeSppLink().apply { connectFailure = IOException("not bonded") }
            val events = mutableListOf<SourceEvent>()
            val job = backgroundScope.launch { source { link }.events().collect { events += it } }
            runCurrent()
            assertEquals(listOf(SourceEvent.SourceError("not bonded", true), SourceEvent.Disconnected), events)
            assertEquals(listOf("connect", "close"), link.calls)
            job.cancelAndJoin()
        }

    @Test
    fun `remote EOF retries with the existing error and closes the link`() =
        runTest {
            val link = FakeSppLink()
            val events = mutableListOf<SourceEvent>()
            val job = backgroundScope.launch { source { link }.events().collect { events += it } }
            runCurrent()
            link.reads.send(Read.End)
            runCurrent()
            assertEquals(
                listOf(
                    SourceEvent.Connected,
                    SourceEvent.SourceError("Connection closed by remote device", true),
                    SourceEvent.Disconnected,
                ),
                events,
            )
            assertEquals(1, link.closeCount)
            job.cancelAndJoin()
        }

    @Test
    fun `connect timeout closes a pending connection at thirty seconds`() =
        runTest {
            val link = FakeSppLink().apply { connectGate = CompletableDeferred() }
            val events = mutableListOf<SourceEvent>()
            val job = backgroundScope.launch { source { link }.events().collect { events += it } }
            runCurrent()
            advanceTimeBy(29_999)
            runCurrent()
            assertTrue(events.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                listOf(SourceEvent.SourceError("Timed out connecting to $address", true), SourceEvent.Disconnected),
                events,
            )
            assertEquals(1, link.closeCount)
            job.cancelAndJoin()
        }

    @Test
    fun `cancellation during connect closes once without emitting a terminal event`() =
        runTest {
            val link = FakeSppLink().apply { connectGate = CompletableDeferred() }
            val events = mutableListOf<SourceEvent>()
            val job = backgroundScope.launch { source { link }.events().collect { events += it } }
            runCurrent()
            job.cancelAndJoin()
            assertEquals(listOf("connect", "close"), link.calls)
            assertEquals(1, link.closeCount)
            assertTrue(events.isEmpty())
        }

    @Test
    fun `collector cancellation releases a genuinely blocking read by closing the link`() =
        runTest {
            val enteredRead = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val exitedRead = CountDownLatch(1)
            var closeCount = 0
            val link =
                object : SppLink {
                    override suspend fun connect() = Unit

                    override val inputStream =
                        SppInput {
                            enteredRead.countDown()
                            try {
                                check(closed.await(5, TimeUnit.SECONDS)) { "read was not released by close" }
                                throw IOException("socket closed")
                            } finally {
                                exitedRead.countDown()
                            }
                        }

                    override fun close() {
                        closeCount++
                        closed.countDown()
                    }
                }
            val events = mutableListOf<SourceEvent>()
            val source = ClassicSppSource(address, null, { link }, Dispatchers.IO, nowMs = { currentTime })
            val job = backgroundScope.launch { source.events().collect { events += it } }
            runCurrent()
            try {
                // Keep the test scheduler parked while the IO thread enters its blocking read.
                assertTrue(enteredRead.await(5, TimeUnit.SECONDS))
                runCurrent()
                job.cancelAndJoin()
                assertEquals(1, closeCount)
                assertEquals(0L, exitedRead.count)
                assertEquals(listOf(SourceEvent.Connected), events)
            } finally {
                job.cancelAndJoin()
                closed.countDown()
            }
        }

    @Test
    fun `close IO exception is ignored and cancellation produces no later events`() =
        runTest {
            val link = FakeSppLink().apply { closeFailure = IOException("already closed") }
            val events = mutableListOf<SourceEvent>()
            val job = backgroundScope.launch { source { link }.events().collect { events += it } }
            runCurrent()
            job.cancelAndJoin()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf(SourceEvent.Connected), events)
            assertEquals(1, link.closeCount)
            assertFalse(events.any { it is SourceEvent.SourceError })
        }

    @Test
    fun `name defaults to address and accepts a friendly name`() =
        runTest {
            assertEquals(address, source { FakeSppLink() }.name)
            assertEquals("Eggfinder", ClassicSppSource(address, "Eggfinder", { FakeSppLink() }).name)
        }

    private sealed interface Read {
        class Bytes(
            val bytes: ByteArray,
        ) : Read

        class Fail(
            val failure: Exception,
        ) : Read

        data object End : Read
    }

    private class FakeSppLink : SppLink {
        val reads = Channel<Read>(Channel.UNLIMITED)
        val calls = mutableListOf<String>()
        val bufferSizes = mutableListOf<Int>()
        var connectFailure: Exception? = null
        var closeFailure: IOException? = null
        var connectGate: CompletableDeferred<Unit>? = null
        var closeCount = 0

        override suspend fun connect() {
            calls += "connect"
            connectGate?.await()
            connectFailure?.let { throw it }
        }

        override val inputStream: SppInput
            get() {
                calls += "input"
                return SppInput { buffer ->
                    bufferSizes += buffer.size
                    when (val read = reads.receive()) {
                        is Read.Bytes -> {
                            read.bytes.copyInto(buffer)
                            read.bytes.size
                        }

                        is Read.Fail -> {
                            throw read.failure
                        }

                        Read.End -> {
                            -1
                        }
                    }
                }
            }

        override fun close() {
            calls += "close"
            closeCount++
            reads.close(IOException("closed"))
            connectGate?.completeExceptionally(IOException("closed"))
            closeFailure?.let { throw it }
        }
    }
}

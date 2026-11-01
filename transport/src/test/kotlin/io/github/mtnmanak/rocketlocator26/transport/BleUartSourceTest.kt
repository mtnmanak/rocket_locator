package io.github.mtnmanak.rocketlocator26.transport

import io.github.mtnmanak.rocketlocator26.transport.BleUartSource.GattMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BleUartSourceTest {
    private val address = "00:14:03:05:59:02"

    @Test
    fun `happy path orders setup operations before Connected and reassembles lines`() =
        runTest {
            val session = start()
            reach(session, Stage.CCCD)
            assertTrue(session.events.isEmpty())
            // UUIDs are literal (HM-10 UART FFE0/FFE1, standard CCCD 0x2902), not the production constants,
            // so a typo in those constants fails this test.
            assertEquals(
                listOf(
                    "connect",
                    "mtu:185",
                    "discover",
                    "service:0000ffe0-0000-1000-8000-00805f9b34fb",
                    "characteristic:0000ffe1-0000-1000-8000-00805f9b34fb",
                    "notify:true",
                    "descriptor:00002902-0000-1000-8000-00805f9b34fb",
                    "write",
                ),
                session.link.calls,
            )
            deliver(session, GattMessage.DescriptorWritten)
            deliver(session, data("first\r\nsec"))
            advanceTimeBy(500)
            deliver(session, data("ond\nthird\r\n"))

            assertEquals(
                listOf(
                    SourceEvent.Connected,
                    SourceEvent.LineReceived("first", 0),
                    SourceEvent.LineReceived("second", 500),
                    SourceEvent.LineReceived("third", 500),
                ),
                session.events,
            )
            session.job.cancelAndJoin()
            assertEquals(1, session.link.closeCount)
        }

    @Test
    fun `clone without CCCD connects without attempting a descriptor write`() =
        runTest {
            val session = start(FakeGattLink().apply { hasDescriptor = false })
            reach(session, Stage.CCCD)
            assertEquals(listOf(SourceEvent.Connected), session.events)
            assertFalse("write" in session.link.calls)
            deliver(session, data("clone\n"))
            assertEquals(SourceEvent.LineReceived("clone", 0), session.events.last())
            session.job.cancelAndJoin()
            assertEquals(1, session.link.closeCount)
        }

    @Test
    fun `failed CCCD write initiation is tolerated without waiting`() =
        runTest {
            val session = start(FakeGattLink().apply { descriptorWriteStarted = false })
            reach(session, Stage.CCCD)
            assertEquals(listOf(SourceEvent.Connected), session.events)
            assertEquals(0L, currentTime)
            deliver(session, data("still notifies\n"))
            assertEquals(SourceEvent.LineReceived("still notifies", 0), session.events.last())
            session.job.cancelAndJoin()
        }

    @Test
    fun `descriptor completion is status agnostic and tolerates unrelated setup messages`() =
        runTest {
            val session = start()
            reach(session, Stage.CCCD)
            deliver(session, GattMessage.MtuChanged)
            deliver(session, GattMessage.ServicesDiscovered(133))
            assertTrue(session.events.isEmpty())
            // The production callback maps every descriptor status, including failure, to this message.
            deliver(session, GattMessage.DescriptorWritten)
            assertEquals(listOf(SourceEvent.Connected), session.events)
            session.job.cancelAndJoin()
        }

    @Test
    fun `CCCD silence connects at exactly two seconds`() =
        runTest {
            val session = start()
            reach(session, Stage.CCCD)
            advanceTimeBy(1999)
            runCurrent()
            assertTrue(session.events.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(SourceEvent.Connected), session.events)
            assertEquals(2000L, currentTime)
            session.job.cancelAndJoin()
        }

    @Test
    fun `early notifications replay exactly once in order across setup and streaming`() =
        runTest {
            val session = start()
            reach(session, Stage.CCCD)
            deliver(session, data("first\r\nsec"))
            advanceTimeBy(250)
            deliver(session, data("ond\nthi"))
            assertTrue(session.events.isEmpty())
            advanceTimeBy(250)
            // Queue completion and later data together to pin the handoff from setup to streaming.
            session.link.messages
                .trySend(GattMessage.DescriptorWritten)
                .getOrThrow()
            session.link.messages
                .trySend(data("rd\n"))
                .getOrThrow()
            runCurrent()
            assertEquals(
                listOf(
                    SourceEvent.Connected,
                    SourceEvent.LineReceived("first", 500),
                    SourceEvent.LineReceived("second", 500),
                    SourceEvent.LineReceived("third", 500),
                ),
                session.events,
            )
            deliver(session, GattMessage.DescriptorWritten)
            runCurrent()
            assertEquals(4, session.events.size)
            session.job.cancelAndJoin()
        }

    @Test
    fun `early notifications do not extend CCCD timeout and replay on timeout`() =
        runTest {
            val session = start()
            reach(session, Stage.CCCD)
            deliver(session, data("first\nsec"))
            advanceTimeBy(1500)
            deliver(session, data("ond\n"))
            advanceTimeBy(500)
            runCurrent()
            assertEquals(
                listOf(
                    SourceEvent.Connected,
                    SourceEvent.LineReceived("first", 2000),
                    SourceEvent.LineReceived("second", 2000),
                ),
                session.events,
            )
            deliver(session, data("third\n"))
            assertEquals(SourceEvent.LineReceived("third", 2000), session.events.last())
            assertEquals(4, session.events.size)
            session.job.cancelAndJoin()
        }

    @Test
    fun `LinkDown in every setup stage retries and closes the GATT client once`() =
        runTest {
            val errors =
                mapOf(
                    Stage.CONNECT to "BLE connection failed (status 133)",
                    Stage.MTU to "BLE link lost during MTU exchange (status 133)",
                    Stage.DISCOVERY to "BLE link lost during service discovery (status 133)",
                    Stage.CCCD to "BLE link lost while enabling notifications (status 133)",
                )
            for ((stage, message) in errors) {
                val session = start()
                reach(session, stage)
                deliver(session, GattMessage.LinkDown(133))
                assertEquals(
                    listOf(SourceEvent.SourceError(message, true), SourceEvent.Disconnected),
                    session.events,
                    stage.name,
                )
                assertEquals(1, session.link.closeCount, stage.name)
                assertTrue(
                    session.link.messages
                        .trySend(data("late\n"))
                        .isFailure,
                )
                session.job.cancelAndJoin()
                assertEquals(1, session.link.closeCount, stage.name)
            }
        }

    @Test
    fun `streaming LinkDown preserves event order and retry flag`() =
        runTest {
            val session = start()
            reach(session, Stage.STREAMING)
            deliver(session, data("last\n"))
            deliver(session, GattMessage.LinkDown(19))
            assertEquals(
                listOf(
                    SourceEvent.Connected,
                    SourceEvent.LineReceived("last", 0),
                    SourceEvent.SourceError("BLE device disconnected (status 19)", true),
                    SourceEvent.Disconnected,
                ),
                session.events,
            )
            assertEquals(1, session.link.closeCount)
            session.job.cancelAndJoin()
        }

    @Test
    fun `streaming watchdog retries at seven seconds and partial bytes restart it`() =
        runTest {
            val session = start()
            reach(session, Stage.STREAMING)
            advanceTimeBy(6000)
            deliver(session, data("partial"))
            advanceTimeBy(6999)
            runCurrent()
            assertEquals(listOf(SourceEvent.Connected), session.events)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                listOf(
                    SourceEvent.Connected,
                    SourceEvent.SourceError("No data received for 7 s (watchdog)", true),
                    SourceEvent.Disconnected,
                ),
                session.events,
            )
            assertEquals(13_000L, currentTime)
            assertEquals(1, session.link.closeCount)
            session.job.cancelAndJoin()
        }

    @Test
    fun `SecurityException before GATT creation is terminal without retry`() =
        runTest {
            val session = start(failure = SecurityException("revoked"))
            assertEquals(
                listOf(
                    SourceEvent.SourceError("Bluetooth permission BLUETOOTH_CONNECT not granted: revoked", false),
                    SourceEvent.Disconnected,
                ),
                session.events,
            )
            assertTrue(session.job.isCompleted)
            assertEquals(0, session.link.closeCount)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, session.attempts.size)
        }

    @Test
    fun `permission revoked in any setup operation is terminal and closes once`() =
        runTest {
            for (operation in listOf("mtu", "discover", "service", "characteristic", "notify", "descriptor", "write")) {
                val link = FakeGattLink().apply { failures[operation] = SecurityException("revoked") }
                val session = start(link)
                val stage =
                    when (operation) {
                        "mtu" -> {
                            Stage.MTU
                        }

                        "discover" -> {
                            Stage.DISCOVERY
                        }

                        else -> {
                            Stage.CCCD
                        }
                    }
                reach(session, stage)
                assertEquals(
                    listOf(
                        SourceEvent.SourceError("Bluetooth permission BLUETOOTH_CONNECT not granted: revoked", false),
                        SourceEvent.Disconnected,
                    ),
                    session.events,
                    operation,
                )
                assertTrue(session.job.isCompleted)
                assertEquals(1, link.closeCount)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, session.attempts.size)
            }
        }

    @Test
    fun `cancellation in every stage closes exactly once and stops all events`() =
        runTest {
            for (stage in Stage.entries) {
                val session = start()
                reach(session, stage)
                session.job.cancelAndJoin()
                val expected = if (stage == Stage.STREAMING) listOf(SourceEvent.Connected) else emptyList()
                assertEquals(expected, session.events, stage.name)
                assertEquals(1, session.link.closeCount, stage.name)
                assertTrue(
                    session.link.messages
                        .trySend(GattMessage.LinkDown(133))
                        .isFailure,
                )
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, session.attempts.size)
                assertEquals(expected, session.events)
            }
        }

    @Test
    fun `missing UART service or characteristic emits the existing terminal error`() =
        runTest {
            for (missingService in listOf(true, false)) {
                val link =
                    FakeGattLink().apply {
                        hasService = !missingService
                        hasCharacteristic = missingService
                    }
                val session = start(link)
                reach(session, Stage.CCCD)
                assertEquals(
                    listOf(
                        SourceEvent.SourceError("Device has no FFE0/FFE1 UART service", false),
                        SourceEvent.Disconnected,
                    ),
                    session.events,
                )
                assertTrue(session.job.isCompleted)
                assertFalse(link.calls.any { it.startsWith("notify:") })
                assertEquals(1, link.closeCount)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, session.attempts.size)
            }
        }

    @Test
    fun `bad address is terminal and adapter or connect failure retries`() =
        runTest {
            val terminal = start(failure = BleUartSource.FatalLinkException("Invalid Bluetooth address \"$address\""))
            assertEquals(
                listOf(
                    SourceEvent.SourceError("Invalid Bluetooth address \"$address\"", false),
                    SourceEvent.Disconnected,
                ),
                terminal.events,
            )
            assertTrue(terminal.job.isCompleted)
            val messages =
                listOf(
                    "Bluetooth is not available on this device",
                    "Bluetooth is turned off",
                    "Unable to initiate BLE connection",
                )
            for (message in messages) {
                val session = start(failure = IOException(message))
                assertEquals(listOf(SourceEvent.SourceError(message, true), SourceEvent.Disconnected), session.events)
                assertEquals(0, session.link.closeCount)
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(2, session.attempts.size)
                assertEquals(session.events.take(2), session.events.drop(2))
                session.job.cancelAndJoin()
            }
        }

    @Test
    fun `MTU refusal and MTU silence both proceed to discovery`() =
        runTest {
            for (mtuStarted in listOf(false, true)) {
                val session = start(FakeGattLink().apply { this.mtuStarted = mtuStarted })
                reach(session, Stage.MTU)
                if (mtuStarted) {
                    advanceTimeBy(1999)
                    runCurrent()
                    assertFalse("discover" in session.link.calls)
                    advanceTimeBy(1)
                    runCurrent()
                }
                assertTrue("discover" in session.link.calls)
                deliver(session, GattMessage.ServicesDiscovered(0))
                deliver(session, GattMessage.DescriptorWritten)
                assertEquals(listOf(SourceEvent.Connected), session.events)
                session.job.cancelAndJoin()
            }
        }

    @Test
    fun `connect and service discovery keep their timeout boundaries`() =
        runTest {
            val cases =
                listOf(
                    Triple(Stage.CONNECT, 30_000L, "Timed out connecting to $address"),
                    Triple(Stage.DISCOVERY, 10_000L, "Timed out discovering GATT services"),
                )
            for ((stage, timeout, message) in cases) {
                val session = start()
                reach(session, stage)
                advanceTimeBy(timeout - 1)
                runCurrent()
                assertTrue(session.events.isEmpty())
                advanceTimeBy(1)
                runCurrent()
                assertEquals(listOf(SourceEvent.SourceError(message, true), SourceEvent.Disconnected), session.events)
                assertEquals(1, session.link.closeCount)
                session.job.cancelAndJoin()
            }
        }

    @Test
    fun `discovery initiation discovery status and notification failures retain retry errors`() =
        runTest {
            val cases =
                listOf(
                    "start" to "Failed to start GATT service discovery",
                    "status" to "GATT service discovery failed (status 133)",
                    "notify" to "Failed to enable notifications on the UART characteristic",
                )
            for ((kind, message) in cases) {
                val link =
                    FakeGattLink().apply {
                        discoveryStarted = kind != "start"
                        notificationsEnabled = kind != "notify"
                    }
                val session = start(link)
                reach(session, Stage.DISCOVERY)
                if (kind != "start") deliver(session, GattMessage.ServicesDiscovered(if (kind == "status") 133 else 0))
                assertEquals(listOf(SourceEvent.SourceError(message, true), SourceEvent.Disconnected), session.events)
                assertEquals(1, link.closeCount)
                session.job.cancelAndJoin()
            }
        }

    @Test
    fun `stale setup messages are ignored until the expected callback arrives`() =
        runTest {
            val session = start()
            deliver(session, GattMessage.DescriptorWritten)
            reach(session, Stage.MTU)
            deliver(session, GattMessage.ServicesDiscovered(133))
            deliver(session, GattMessage.MtuChanged)
            deliver(session, GattMessage.LinkUp)
            deliver(session, GattMessage.ServicesDiscovered(0))
            deliver(session, GattMessage.DescriptorWritten)
            assertEquals(listOf(SourceEvent.Connected), session.events)
            session.job.cancelAndJoin()
        }

    @Test
    fun `backoff doubles before connection and resets after successful setup`() =
        runTest {
            val links = List(4) { FakeGattLink() }
            val attempts = mutableListOf<Long>()
            val events = mutableListOf<SourceEvent>()
            val source =
                BleUartSource(
                    address,
                    null,
                    { messages ->
                        val link = links[attempts.size]
                        attempts += currentTime
                        link.messages = messages
                        link
                    },
                    nowMs = { currentTime },
                )
            val job = backgroundScope.launch { source.events().collect { events += it } }
            runCurrent()
            links[0].messages.trySend(GattMessage.LinkDown(133)).getOrThrow()
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()
            links[1].messages.trySend(GattMessage.LinkDown(133)).getOrThrow()
            runCurrent()
            advanceTimeBy(1999)
            runCurrent()
            assertEquals(listOf(0L, 1000L), attempts)
            advanceTimeBy(1)
            runCurrent()
            val connected = Session(links[2], events, attempts, job)
            reach(connected, Stage.STREAMING)
            deliver(connected, GattMessage.LinkDown(19))
            advanceTimeBy(999)
            runCurrent()
            assertEquals(3, attempts.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(0L, 1000L, 3000L, 4000L), attempts)
            assertEquals(
                listOf(
                    SourceEvent.SourceError("BLE connection failed (status 133)", true),
                    SourceEvent.Disconnected,
                    SourceEvent.SourceError("BLE connection failed (status 133)", true),
                    SourceEvent.Disconnected,
                    SourceEvent.Connected,
                    SourceEvent.SourceError("BLE device disconnected (status 19)", true),
                    SourceEvent.Disconnected,
                ),
                events,
            )
            assertTrue(links.take(3).all { it.closeCount == 1 })
            job.cancelAndJoin()
            assertTrue(links.all { it.closeCount == 1 })
        }

    @Test
    fun `permission failure while closing is ignored without a second close`() =
        runTest {
            for (cancel in listOf(false, true)) {
                val session = start(FakeGattLink().apply { closeFailure = SecurityException("revoked") })
                reach(session, Stage.STREAMING)
                if (!cancel) deliver(session, GattMessage.LinkDown(19))
                session.job.cancelAndJoin()
                assertEquals(1, session.link.closeCount)
                if (cancel) assertEquals(listOf(SourceEvent.Connected), session.events)
            }
        }

    @Test
    fun `name falls back to address without creating a GATT client`() {
        val factory = GattLinkFactory { error("must stay cold") }
        assertEquals(address, BleUartSource(address, null, factory).name)
        assertEquals("HM-10", BleUartSource(address, "HM-10", factory).name)
    }

    private fun TestScope.start(
        link: FakeGattLink = FakeGattLink(),
        failure: Exception? = null,
    ): Session {
        val events = mutableListOf<SourceEvent>()
        val attempts = mutableListOf<Long>()
        val source =
            BleUartSource(
                deviceAddress = address,
                deviceName = null,
                linkFactory = { messages ->
                    attempts += currentTime
                    failure?.let { throw it }
                    link.calls += "connect"
                    link.messages = messages
                    link
                },
                nowMs = { currentTime },
            )
        val job = backgroundScope.launch { source.events().collect { events += it } }
        runCurrent()
        return Session(link, events, attempts, job)
    }

    private fun TestScope.deliver(
        session: Session,
        message: GattMessage,
    ) {
        session.link.messages
            .trySend(message)
            .getOrThrow()
        runCurrent()
    }

    /** Stops with the source waiting in the requested stage, without advancing virtual time. */
    private fun TestScope.reach(
        session: Session,
        stage: Stage,
    ) {
        if (stage == Stage.CONNECT) return
        deliver(session, GattMessage.LinkUp)
        if (stage == Stage.MTU) return
        deliver(session, GattMessage.MtuChanged)
        if (stage == Stage.DISCOVERY) return
        deliver(session, GattMessage.ServicesDiscovered(0))
        if (stage == Stage.CCCD) return
        deliver(session, GattMessage.DescriptorWritten)
    }

    private fun data(text: String): GattMessage.Data = GattMessage.Data(text.toByteArray(Charsets.US_ASCII))

    private enum class Stage {
        CONNECT,
        MTU,
        DISCOVERY,
        CCCD,
        STREAMING,
    }

    private class Session(
        val link: FakeGattLink,
        val events: MutableList<SourceEvent>,
        val attempts: MutableList<Long>,
        val job: Job,
    )

    private class FakeGattLink : GattLink {
        lateinit var messages: Channel<GattMessage>
        val calls = mutableListOf<String>()
        val failures = mutableMapOf<String, Exception>()
        var mtuStarted = true
        var discoveryStarted = true
        var hasService = true
        var hasCharacteristic = true
        var hasDescriptor = true
        var notificationsEnabled = true
        var descriptorWriteStarted = true
        var closeFailure: SecurityException? = null
        var closeCount = 0

        private val descriptor = object : GattDescriptor {}
        private val characteristic =
            GattCharacteristic { uuid ->
                record("descriptor:$uuid", "descriptor")
                if (hasDescriptor) descriptor else null
            }
        private val service =
            GattService { uuid ->
                record("characteristic:$uuid", "characteristic")
                if (hasCharacteristic) characteristic else null
            }

        override fun requestMtu(mtu: Int): Boolean {
            record("mtu:$mtu", "mtu")
            return mtuStarted
        }

        override fun discoverServices(): Boolean {
            record("discover")
            return discoveryStarted
        }

        override fun getService(uuid: UUID): GattService? {
            record("service:$uuid", "service")
            return if (hasService) service else null
        }

        override fun setCharacteristicNotification(
            characteristic: GattCharacteristic,
            enabled: Boolean,
        ): Boolean {
            assertSame(this.characteristic, characteristic)
            record("notify:$enabled", "notify")
            return notificationsEnabled
        }

        override fun writeDescriptor(descriptor: GattDescriptor): Boolean {
            assertSame(this.descriptor, descriptor)
            record("write")
            return descriptorWriteStarted
        }

        override fun close() {
            calls += "close"
            closeCount++
            closeFailure?.let { throw it }
        }

        private fun record(
            call: String,
            operation: String = call,
        ) {
            calls += call
            failures[operation]?.let { throw it }
        }
    }
}

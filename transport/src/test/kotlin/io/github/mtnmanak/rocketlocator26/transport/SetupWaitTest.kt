package io.github.mtnmanak.rocketlocator26.transport

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupWaitTest {

    @Test
    fun `early notifications do not restart the setup deadline`() = runTest {
        val messages = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch {
            repeat(100) {
                messages.send("fragment$it")
                delay(250)
            }
        }

        val pending = awaitSetupCompletion(messages, 2000) { it == "descriptor" }

        assertEquals(2000L, currentTime)
        assertEquals(List(8) { "fragment$it" }, pending)
    }

    @Test
    fun `descriptor completion preserves early fragments and leaves later data queued`() = runTest {
        val messages = Channel<String>(Channel.UNLIMITED)
        launch {
            messages.send("\$GPGGA,early\r\n\$GPR")
            delay(250)
            messages.send("MC,partial")
            delay(250)
            messages.send("descriptor")
            messages.send(",late\r\n")
        }

        val pending = awaitSetupCompletion(messages, 2000) { it == "descriptor" }

        assertEquals(500L, currentTime)
        assertEquals(listOf("\$GPGGA,early\r\n\$GPR", "MC,partial"), pending)
        val assembler = NmeaLineAssembler()
        val lines = pending.flatMap { assembler.feed(it.toByteArray(Charsets.US_ASCII)) } +
            assembler.feed(messages.receive().toByteArray(Charsets.US_ASCII))
        assertEquals(listOf("\$GPGGA,early", "\$GPRMC,partial,late"), lines)
    }

    @Test
    fun `silent setup times out relative to the start of the wait`() = runTest {
        val messages = Channel<String>(Channel.UNLIMITED)
        delay(700)

        assertTrue(awaitSetupCompletion(messages, 2000) { false }.isEmpty())

        assertEquals(2700L, currentTime)
    }

    @Test
    fun `link failure propagates immediately instead of waiting for the deadline`() = runTest {
        val messages = Channel<String>(Channel.UNLIMITED)
        val failure = IOException("link lost")
        launch {
            delay(250)
            messages.send("link down")
        }
        var caught: IOException? = null

        try {
            awaitSetupCompletion(messages, 2000) { throw failure }
        } catch (e: IOException) {
            caught = e
        }

        // Not assertSame: kotlinx-coroutines stack-trace recovery (on under -ea) may rethrow a copy.
        assertEquals(failure.message, caught?.message)
        assertEquals(250L, currentTime)
    }

    @Test
    fun `cancellation propagates without completing setup`() = runTest {
        val messages = Channel<String>(Channel.UNLIMITED)
        var completed = false
        val job = launch {
            awaitSetupCompletion(messages, 2000) { false }
            completed = true
        }
        advanceTimeBy(250)

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(completed)
        assertEquals(250L, currentTime)
    }
}

package io.github.mtnmanak.rocketlocator26.transport

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconnectPolicyTest {

    @Test
    fun `default schedule doubles from one second and caps at fifteen`() {
        val policy = ReconnectPolicy()

        val delays = List(7) { policy.nextDelayMs() }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L), delays)
    }

    @Test
    fun `stays at the cap indefinitely`() {
        val policy = ReconnectPolicy()
        repeat(5) { policy.nextDelayMs() } // Advance to the cap.

        repeat(50) {
            assertEquals(15_000L, policy.nextDelayMs())
        }
    }

    @Test
    fun `reset returns the schedule to the initial delay`() {
        val policy = ReconnectPolicy()
        repeat(4) { policy.nextDelayMs() } // 1000, 2000, 4000, 8000.

        policy.reset()

        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
    }

    @Test
    fun `custom initial and max delays are honoured`() {
        val policy = ReconnectPolicy(initialDelayMs = 500, maxDelayMs = 3_000)

        val delays = List(5) { policy.nextDelayMs() }

        assertEquals(listOf(500L, 1_000L, 2_000L, 3_000L, 3_000L), delays)
    }

    @Test
    fun R2C004_reconnectPolicyOverflow_staysPositiveAndCapped() {
        val initialDelayMs = 1L shl 62
        val maxDelayMs = Long.MAX_VALUE
        val policy = ReconnectPolicy(initialDelayMs = initialDelayMs, maxDelayMs = maxDelayMs)
        // Inspect returned millisecond values only; never wait or exercise a radio.
        val delays = List(4) { policy.nextDelayMs() }
        assertAll(
            { assertTrue(delays.all { it > 0L && it <= maxDelayMs }, "Positive capped delays required: $delays ms") },
            { assertEquals(listOf(initialDelayMs, maxDelayMs, maxDelayMs, maxDelayMs), delays,
                "Doubling must saturate at the cap before Long overflow") },
        )
    }

    @Test
    fun `doubling at half of Long MAX stays exact then saturates`() {
        val initialDelayMs = Long.MAX_VALUE / 2
        val policy = ReconnectPolicy(initialDelayMs = initialDelayMs, maxDelayMs = Long.MAX_VALUE)

        assertEquals(
            listOf(initialDelayMs, Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE),
            List(4) { policy.nextDelayMs() },
        )
        policy.reset()
        assertEquals(initialDelayMs, policy.nextDelayMs())
    }

    @Test
    fun `Long MAX initial delay stays capped`() {
        val policy = ReconnectPolicy(initialDelayMs = Long.MAX_VALUE, maxDelayMs = Long.MAX_VALUE)

        assertEquals(List(4) { Long.MAX_VALUE }, List(4) { policy.nextDelayMs() })
    }

    @Test
    fun `rejects non-positive initial delay and inverted bounds`() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(initialDelayMs = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy(initialDelayMs = 2_000, maxDelayMs = 1_000)
        }
    }
}

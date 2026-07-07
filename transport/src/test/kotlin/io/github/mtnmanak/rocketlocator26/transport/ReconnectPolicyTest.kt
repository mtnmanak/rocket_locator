package io.github.mtnmanak.rocketlocator26.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `rejects non-positive initial delay and inverted bounds`() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(initialDelayMs = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy(initialDelayMs = 2_000, maxDelayMs = 1_000)
        }
    }
}

package com.moonkata.textreader.tts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the tick timing of timer-based auto page-turning. Instead of actually waiting several
 * seconds, this uses kotlinx-coroutines-test's virtual time (TestScope/advanceTimeBy) to verify
 * it instantly and deterministically — TESTING.md's note that "real timing of timer auto-advance
 * is excluded as unreliable" refers to using real time; virtual time lets it be verified reliably.
 * AutoPageTurnController itself is pure coroutine code with no Android dependency, so this is a
 * plain JUnit test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoPageTurnControllerTest {

    @Test
    fun start_callsOnTickExactlyOnceAtEachInterval() = runTest {
        var tickCount = 0
        val controller = AutoPageTurnController(scope = this) { tickCount++ }

        controller.start(intervalSeconds = 5)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, tickCount)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, tickCount)

        controller.stop()
    }

    @Test
    fun stop_cancelsTheTimer_noMoreTicksAfterwards() = runTest {
        var tickCount = 0
        val controller = AutoPageTurnController(scope = this) { tickCount++ }

        controller.start(intervalSeconds = 5)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, tickCount)

        controller.stop()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals("No more ticks should arrive after stop()", 1, tickCount)
    }

    @Test
    fun callingStartAgain_cancelsThePreviousTimerAndUsesTheNewInterval() = runTest {
        var tickCount = 0
        val controller = AutoPageTurnController(scope = this) { tickCount++ }

        controller.start(intervalSeconds = 100) // An interval long enough to never elapse within this test
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("The first tick should not have arrived yet", 0, tickCount)

        controller.start(intervalSeconds = 2) // Restart — the previous 100s timer is cancelled and a new one starts
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals("After restarting, the tick should arrive based on the new interval (2s)", 1, tickCount)

        controller.stop()
    }

    @Test
    fun stop_withoutHavingStarted_doesNotThrow() = runTest {
        val controller = AutoPageTurnController(scope = this) { }

        controller.stop()
    }
}

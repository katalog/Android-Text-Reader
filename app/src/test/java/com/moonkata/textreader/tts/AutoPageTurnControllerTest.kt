package com.moonkata.textreader.tts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 타이머 기반 자동 페이지 넘김의 tick 타이밍 검증. 실제 몇 초씩 기다리는 대신
 * kotlinx-coroutines-test의 가상 시간(TestScope/advanceTimeBy)으로 즉시, 결정적으로 검증한다 —
 * TESTING.md에 "타이머 자동넘김의 실제 타이밍은 신뢰성 낮아 제외"라고 적어뒀던 건 실제 시간을 쓰는
 * 경우 얘기고, 가상 시간으로는 안정적으로 검증 가능하다. AutoPageTurnController 자체는 Android
 * 의존성이 없는 순수 코루틴 코드라 일반 JUnit으로 둔다.
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

        assertEquals("stop() 이후엔 더 이상 tick이 오면 안 됨", 1, tickCount)
    }

    @Test
    fun callingStartAgain_cancelsThePreviousTimerAndUsesTheNewInterval() = runTest {
        var tickCount = 0
        val controller = AutoPageTurnController(scope = this) { tickCount++ }

        controller.start(intervalSeconds = 100) // 이 테스트 안에서는 절대 안 끝날 정도로 긴 간격
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("아직 첫 tick도 안 왔어야 함", 0, tickCount)

        controller.start(intervalSeconds = 2) // 재시작 — 이전 타이머(100초짜리)는 취소되고 새로 시작
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals("재시작 후엔 새 간격(2초) 기준으로 tick이 와야 함", 1, tickCount)

        controller.stop()
    }

    @Test
    fun stop_withoutHavingStarted_doesNotThrow() = runTest {
        val controller = AutoPageTurnController(scope = this) { }

        controller.stop()
    }
}

package com.moonkata.textreader.data.parser

import com.moonkata.textreader.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 챕터 점프 N등분 breakpoint 계산과 다음/이전 탐색의 순수 로직 검증. Android 의존성이 전혀 없어
 * 기기/에뮬레이터 없이 JVM에서 바로 도는 일반 JUnit 테스트로 둔다(androidTest가 아님).
 */
class ChapterJumpNavigatorTest {

    private val chapters = listOf(
        Chapter("1장", charOffset = 0),
        Chapter("2장", charOffset = 100),
        Chapter("3장", charOffset = 300),
    )
    private val totalCharCount = 400

    @Test
    fun breakpoints_divideEachChapterIntoEqualFractions() {
        // 1장: 0~100(길이100) 4등분 -> 25,50,75,100 / 2장: 100~300(길이200) 4등분 -> 150,200,250,300
        // 3장: 300~400(길이100, 마지막 챕터라 끝은 totalCharCount) 4등분 -> 325,350,375,400
        val points = ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 4)

        assertEquals(
            listOf(25, 50, 75, 100, 150, 200, 250, 300, 325, 350, 375, 400),
            points,
        )
    }

    @Test
    fun nextBreakpoint_returnsTheFirstPointStrictlyAfterCurrentOffset() {
        val points = ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 4)

        assertEquals(25, ChapterJumpNavigator.nextBreakpoint(points, currentOffset = 0))
        assertEquals(50, ChapterJumpNavigator.nextBreakpoint(points, currentOffset = 25))
        assertNull("마지막 지점 이후엔 다음이 없어야 함", ChapterJumpNavigator.nextBreakpoint(points, currentOffset = 400))
    }

    @Test
    fun previousBreakpoint_returnsTheLastPointStrictlyBeforeCurrentOffset() {
        val points = ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 4)

        assertEquals(375, ChapterJumpNavigator.previousBreakpoint(points, currentOffset = 400))
        assertEquals(350, ChapterJumpNavigator.previousBreakpoint(points, currentOffset = 375))
        assertNull("첫 지점 이전엔 이전이 없어야 함", ChapterJumpNavigator.previousBreakpoint(points, currentOffset = 25))
    }

    @Test
    fun forwardOverAllBreakpointsThenBackward_returnsToTheFirstBreakpoint() {
        val points = ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 4)

        var offset = 0
        repeat(points.size) {
            offset = ChapterJumpNavigator.nextBreakpoint(points, offset) ?: offset
        }
        assertEquals("끝까지 다음을 반복하면 마지막 지점에 도착해야 함", points.last(), offset)

        repeat(points.size - 1) {
            offset = ChapterJumpNavigator.previousBreakpoint(points, offset) ?: offset
        }
        assertEquals("마지막 지점에서 (지점 수 - 1)번 이전으로 돌아오면 첫 지점이어야 함", points.first(), offset)
    }

    @Test
    fun emptyChapterList_producesNoBreakpoints() {
        assertEquals(emptyList<Int>(), ChapterJumpNavigator.breakpoints(emptyList(), totalCharCount, divisions = 4))
    }

    @Test
    fun divisionsLessThanOne_producesNoBreakpoints() {
        assertEquals(emptyList<Int>(), ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 0))
    }

    @Test
    fun zeroLengthTrailingChapter_isSkippedWithoutProducingBreakpoints() {
        // 마지막 챕터가 책 끝과 같은 오프셋(길이 0)이면 나눌 구간이 없어 건너뛰어야 한다.
        val withZeroLengthTail = chapters + Chapter("4장(빈 챕터)", charOffset = totalCharCount)
        val points = ChapterJumpNavigator.breakpoints(withZeroLengthTail, totalCharCount, divisions = 4)

        assertEquals(listOf(25, 50, 75, 100, 150, 200, 250, 300, 325, 350, 375, 400), points)
    }
}

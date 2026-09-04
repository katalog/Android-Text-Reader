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

    // 챕터 간 줄 수 임계값(20줄) 판정에 실제로 쓰이므로, 각 구간에 임계값을 넉넉히 넘는 줄바꿈을 채워둔다
    // ("a\n" 반복 -> 2글자당 줄바꿈 1개, 100글자 구간이면 약 50줄).
    private val text = "a\n".repeat(totalCharCount / 2)

    @Test
    fun breakpoints_divideEachChapterIntoEqualFractions() {
        // 1장: 0~100(길이100) 4등분 -> 25,50,75,100 / 2장: 100~300(길이200) 4등분 -> 150,200,250,300
        // 3장: 300~400(길이100, 마지막 챕터라 끝은 totalCharCount) 4등분 -> 325,350,375,400
        val points = ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 4, text = text)

        assertEquals(
            listOf(25, 50, 75, 100, 150, 200, 250, 300, 325, 350, 375, 400),
            points,
        )
    }

    @Test
    fun nextBreakpoint_returnsTheFirstPointStrictlyAfterCurrentOffset() {
        val points = ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 4, text = text)

        assertEquals(25, ChapterJumpNavigator.nextBreakpoint(points, currentOffset = 0))
        assertEquals(50, ChapterJumpNavigator.nextBreakpoint(points, currentOffset = 25))
        assertNull("마지막 지점 이후엔 다음이 없어야 함", ChapterJumpNavigator.nextBreakpoint(points, currentOffset = 400))
    }

    @Test
    fun previousBreakpoint_returnsTheLastPointStrictlyBeforeCurrentOffset() {
        val points = ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 4, text = text)

        assertEquals(375, ChapterJumpNavigator.previousBreakpoint(points, currentOffset = 400))
        assertEquals(350, ChapterJumpNavigator.previousBreakpoint(points, currentOffset = 375))
        assertNull("첫 지점 이전엔 이전이 없어야 함", ChapterJumpNavigator.previousBreakpoint(points, currentOffset = 25))
    }

    @Test
    fun forwardOverAllBreakpointsThenBackward_returnsToTheFirstBreakpoint() {
        val points = ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 4, text = text)

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
        assertEquals(emptyList<Int>(), ChapterJumpNavigator.breakpoints(emptyList(), totalCharCount, divisions = 4, text = text))
    }

    @Test
    fun divisionsLessThanOne_producesNoBreakpoints() {
        assertEquals(emptyList<Int>(), ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 0, text = text))
    }

    @Test
    fun zeroLengthTrailingChapter_isSkippedWithoutProducingBreakpoints() {
        // 마지막 챕터가 책 끝과 같은 오프셋(길이 0)이면 나눌 구간이 없어 건너뛰어야 한다.
        val withZeroLengthTail = chapters + Chapter("4장(빈 챕터)", charOffset = totalCharCount)
        val points = ChapterJumpNavigator.breakpoints(withZeroLengthTail, totalCharCount, divisions = 4, text = text)

        assertEquals(listOf(25, 50, 75, 100, 150, 200, 250, 300, 325, 350, 375, 400), points)
    }

    @Test
    fun chapterPatternsWithinLineThreshold_areNotSubdividedButJumpedStraightThrough() {
        // .docs/IDEAS.md 예시: "## 추신: ..." 공지문 바로 다음 줄에 진짜 챕터 제목이 붙어 있는 경우 —
        // 그 사이(0줄, 임계값 20줄 이하)는 등분하지 않고 챕터 패턴에서 챕터 패턴으로 곧장 건너뛴다.
        val closeChapters = listOf(
            Chapter("1장", charOffset = 0),
            Chapter("추신", charOffset = 100),
            Chapter("2장", charOffset = 110),
        )
        val closeTotalCharCount = 200
        // [0,100): 줄바꿈 50개(임계값 초과, 정상 등분) / [100,110): 줄바꿈 0개(임계값 이하, 곧장 점프)
        // [110,200): 마지막 챕터라 다음 패턴이 없어 줄 수와 무관하게 항상 정상 등분.
        val closeText = "a\n".repeat(50) + "b".repeat(10) + "c".repeat(90)

        val points = ChapterJumpNavigator.breakpoints(closeChapters, closeTotalCharCount, divisions = 4, text = closeText)

        assertEquals(
            listOf(25, 50, 75, 100, 110, 132, 155, 177, 200),
            points,
        )
    }
}

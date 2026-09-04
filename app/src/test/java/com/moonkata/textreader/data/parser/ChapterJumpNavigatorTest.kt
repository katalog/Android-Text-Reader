package com.moonkata.textreader.data.parser

import com.moonkata.textreader.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the pure logic of chapter-jump N-way breakpoint calculation and next/previous
 * navigation. Has no Android dependency at all, so it's a plain JUnit test that runs directly
 * on the JVM without a device/emulator (not androidTest).
 */
class ChapterJumpNavigatorTest {

    private val chapters = listOf(
        Chapter("1장", charOffset = 0),
        Chapter("2장", charOffset = 100),
        Chapter("3장", charOffset = 300),
    )
    private val totalCharCount = 400

    // Actually used to evaluate the inter-chapter line-count threshold (20 lines), so each span
    // is filled with enough line breaks to comfortably exceed the threshold
    // ("a\n" repeated -> 1 line break per 2 chars, so a 100-char span is about 50 lines).
    private val text = "a\n".repeat(totalCharCount / 2)

    @Test
    fun breakpoints_divideEachChapterIntoEqualFractions() {
        // Chapter 1: 0~100 (length 100) split into 4 -> 25,50,75,100 / Chapter 2: 100~300 (length 200) split into 4 -> 150,200,250,300
        // Chapter 3: 300~400 (length 100; being the last chapter, its end is totalCharCount) split into 4 -> 325,350,375,400
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
        assertNull("There should be no next point after the last one", ChapterJumpNavigator.nextBreakpoint(points, currentOffset = 400))
    }

    @Test
    fun previousBreakpoint_returnsTheLastPointStrictlyBeforeCurrentOffset() {
        val points = ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 4, text = text)

        assertEquals(375, ChapterJumpNavigator.previousBreakpoint(points, currentOffset = 400))
        assertEquals(350, ChapterJumpNavigator.previousBreakpoint(points, currentOffset = 375))
        assertNull("There should be no previous point before the first one", ChapterJumpNavigator.previousBreakpoint(points, currentOffset = 25))
    }

    @Test
    fun forwardOverAllBreakpointsThenBackward_returnsToTheFirstBreakpoint() {
        val points = ChapterJumpNavigator.breakpoints(chapters, totalCharCount, divisions = 4, text = text)

        var offset = 0
        repeat(points.size) {
            offset = ChapterJumpNavigator.nextBreakpoint(points, offset) ?: offset
        }
        assertEquals("Repeating next() all the way through should land on the last point", points.last(), offset)

        repeat(points.size - 1) {
            offset = ChapterJumpNavigator.previousBreakpoint(points, offset) ?: offset
        }
        assertEquals("Going back (point count - 1) times from the last point should reach the first point", points.first(), offset)
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
        // If the last chapter has the same offset as the end of the book (length 0), there's no
        // span to divide, so it should be skipped.
        val withZeroLengthTail = chapters + Chapter("4장(빈 챕터)", charOffset = totalCharCount)
        val points = ChapterJumpNavigator.breakpoints(withZeroLengthTail, totalCharCount, divisions = 4, text = text)

        assertEquals(listOf(25, 50, 75, 100, 150, 200, 250, 300, 325, 350, 375, 400), points)
    }

    @Test
    fun chapterPatternsWithinLineThreshold_areNotSubdividedButJumpedStraightThrough() {
        // Example from .docs/IDEAS.md: when a real chapter title immediately follows a notice
        // line like "## Postscript: ..." on the very next line — the gap between them (0 lines,
        // at or below the 20-line threshold) is not subdivided; it jumps straight from one
        // chapter pattern to the next.
        val closeChapters = listOf(
            Chapter("1장", charOffset = 0),
            Chapter("추신", charOffset = 100),
            Chapter("2장", charOffset = 110),
        )
        val closeTotalCharCount = 200
        // [0,100): 50 line breaks (over the threshold, subdivided normally) / [100,110): 0 line breaks (at or below the threshold, jump straight through)
        // [110,200): being the last chapter with no following pattern, it's always subdivided normally regardless of line count.
        val closeText = "a\n".repeat(50) + "b".repeat(10) + "c".repeat(90)

        val points = ChapterJumpNavigator.breakpoints(closeChapters, closeTotalCharCount, divisions = 4, text = closeText)

        assertEquals(
            listOf(25, 50, 75, 100, 110, 132, 155, 177, 200),
            points,
        )
    }
}

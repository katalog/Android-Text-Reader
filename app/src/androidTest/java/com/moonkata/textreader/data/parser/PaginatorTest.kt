package com.moonkata.textreader.data.parser

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.model.Paragraph
import com.moonkata.textreader.testutil.TestTextMeasurer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edge-case tests targeting `Paginator` directly, without `ReaderViewModel` — placed in androidTest
 * because a real `TextMeasurer` is needed (`TestTextMeasurer`). Existing tests like
 * `PageNavigationRoundTripTest` only verify the "normal" round-trip contract using a real novel; this
 * file instead targets edges that rarely show up in an ordinary book — empty text, a zero-size
 * viewport, an extremely long paragraph that doesn't fit on one page.
 */
@RunWith(AndroidJUnit4::class)
class PaginatorTest {

    private val textMeasurer = TestTextMeasurer.create(ApplicationProvider.getApplicationContext<Application>())

    private fun params(widthPx: Int = 1000, heightPx: Int = 2000) = PaginationParams(
        fontFamily = FontFamily.Default,
        fontSizeSp = 18f.sp,
        lineHeightMultiplier = 1.5f,
        letterSpacingSp = 0f.sp,
        contentWidthPx = widthPx,
        contentHeightPx = heightPx,
        textColor = Color.Black,
    )

    @Test
    fun emptyParagraphList_returnsNoPages_regardlessOfText() {
        val pages = Paginator.paginateFrom("", emptyList(), fromOffset = 0, textMeasurer, params(), maxPages = 5)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun zeroWidthViewport_returnsNoPages() {
        val text = "본문 내용입니다."
        val paragraphs = listOf(Paragraph(text, 0, text.length))
        val pages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(widthPx = 0), maxPages = 5)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun zeroHeightViewport_returnsNoPages() {
        val text = "본문 내용입니다."
        val paragraphs = listOf(Paragraph(text, 0, text.length))
        val pages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(heightPx = 0), maxPages = 5)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun shortSingleParagraph_fitsEntirelyInOnePage() {
        val text = "짧은 문단 하나."
        val paragraphs = listOf(Paragraph(text, 0, text.length))
        val pages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(), maxPages = 5)
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].startOffset)
        assertEquals(text.length, pages[0].endOffset)
    }

    @Test
    fun extremelyLongSingleParagraph_thatDoesNotFitOnePage_splitsAcrossMultiplePages() {
        // If a single paragraph with no line breaks at all is much longer than the viewport, it must
        // keep getting split across multiple pages without advancing the paragraph index (the "index
        // stays the same" branch in paginateCore).
        val text = "가".repeat(4000)
        val paragraphs = listOf(Paragraph(text, 0, text.length))

        val pages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(widthPx = 300, heightPx = 200), maxPages = 20)

        assertEquals("A single very long paragraph should be split across multiple pages", 20, pages.size)
        // Should be a contiguous range (the text has no blank lines, so skipLeadingBlankLines skips nothing).
        for (i in 0 until pages.size - 1) {
            assertEquals("The end of page $i should be the start of the next page", pages[i].endOffset, pages[i + 1].startOffset)
        }
        assertEquals(0, pages.first().startOffset)
        // Every page should actually make forward progress (guards against an infinite loop/stall).
        pages.forEach { assertTrue("A page should always make progress (${it})", it.endOffset > it.startOffset) }
    }

    @Test
    fun extremelySmallViewport_stillMakesProgressWithoutHanging() {
        // If contentHeightPx is smaller than a single line, fitLines can come out to 0 — Paginator
        // forces at least 1 line in that case to avoid an infinite loop (fitLines == 0 -> 1 fallback).
        // This confirms that fallback actually makes progress on every page.
        val text = "가".repeat(500)
        val paragraphs = listOf(Paragraph(text, 0, text.length))

        val pages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(widthPx = 300, heightPx = 1), maxPages = 10)

        assertEquals(10, pages.size)
        pages.forEach { assertTrue(it.endOffset > it.startOffset) }
    }

    @Test
    fun leadingBlankLinesAtAPageStart_areSkipped() {
        // If the blank line used to separate paragraphs carries over to the top of the next page, it
        // shows up as a phantom blank line at the top of the screen — force an overflow of exactly
        // one page's worth and check that the new page's start skips those line breaks.
        val firstPart = "가".repeat(200)
        val text = firstPart + "\n\n\n" + "나".repeat(200)
        val paragraphs = listOf(
            Paragraph(firstPart, 0, firstPart.length),
            Paragraph("나".repeat(200), firstPart.length + 3, text.length),
        )

        val pages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(widthPx = 300, heightPx = 200), maxPages = 30)

        val pageCrossingTheBoundary = pages.indexOfFirst { it.startOffset < firstPart.length && it.endOffset >= firstPart.length }
        if (pageCrossingTheBoundary != -1 && pageCrossingTheBoundary + 1 < pages.size) {
            val nextPageStart = pages[pageCrossingTheBoundary + 1].startOffset
            assertTrue(
                "The next page's start should not be a line-break character (offset $nextPageStart)",
                nextPageStart >= text.length || (text[nextPageStart] != '\n' && text[nextPageStart] != '\r'),
            )
        }
    }

    @Test
    fun maxPages_limitsHowManyPageBreaksAreReturned_evenWhenMoreTextRemains() {
        val text = "가".repeat(4000)
        val paragraphs = listOf(Paragraph(text, 0, text.length))
        val pages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(widthPx = 300, heightPx = 200), maxPages = 3)
        assertEquals(3, pages.size)
        assertTrue("There should be leftover text that wasn't paginated", pages.last().endOffset < text.length)
    }

    @Test
    fun fromOffsetInsideAParagraph_startsThatPageAtTheGivenOffset() {
        val text = "가".repeat(100) + "나".repeat(100)
        val paragraphs = listOf(Paragraph(text, 0, text.length))
        val midOffset = 60

        val pages = Paginator.paginateFrom(text, paragraphs, fromOffset = midOffset, textMeasurer, params(), maxPages = 1)

        assertEquals(midOffset, pages.first().startOffset)
    }

    // --- onePageEndingAt ---

    @Test
    fun onePageEndingAt_zeroOrNegativeEndOffset_returnsNull() {
        val text = "가".repeat(100)
        val paragraphs = listOf(Paragraph(text, 0, text.length))
        assertNull(Paginator.onePageEndingAt(text, paragraphs, endOffset = 0, textMeasurer, params(), referenceSpanChars = 500))
        assertNull(Paginator.onePageEndingAt(text, paragraphs, endOffset = -10, textMeasurer, params(), referenceSpanChars = 500))
    }

    @Test
    fun onePageEndingAt_emptyParagraphs_returnsNull() {
        assertNull(Paginator.onePageEndingAt("", emptyList(), endOffset = 100, textMeasurer, params(), referenceSpanChars = 500))
    }

    @Test
    fun onePageEndingAt_reconstructsAPageThatActuallyEndsAtTheRequestedOffset() {
        // Reproduces the real usage path: compute a few pages forward to get a real page boundary,
        // then check that onePageEndingAt can reconstruct that same boundary from just its end offset
        // (this is the actual path used to estimate the "previous" page with no history, e.g. right
        // after a search jump).
        val text = "가".repeat(4000)
        val paragraphs = listOf(Paragraph(text, 0, text.length))
        val forwardPages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(widthPx = 300, heightPx = 200), maxPages = 5)
        val targetEnd = forwardPages[2].endOffset

        val reconstructed = Paginator.onePageEndingAt(text, paragraphs, endOffset = targetEnd, textMeasurer, params(widthPx = 300, heightPx = 200), referenceSpanChars = 500)

        assertEquals(targetEnd, reconstructed?.endOffset)
    }
}

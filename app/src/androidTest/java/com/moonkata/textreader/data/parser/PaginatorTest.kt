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
 * `Paginator`를 `ReaderViewModel` 없이 직접 겨냥한 경계 케이스 테스트 — 실제 `TextMeasurer`가
 * 필요해(`TestTextMeasurer`) androidTest에 둔다. `PageNavigationRoundTripTest` 등 기존 테스트는
 * 실제 소설로 "정상적인" 왕복 계약만 검증하는데, 여기서는 빈 텍스트/뷰포트 0/한 페이지에 안 들어가는
 * 극단적으로 긴 문단처럼 일반적인 책에서는 잘 안 나오는 경계를 겨냥한다.
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
        // 개행이 하나도 없는 문단 하나가 뷰포트보다 훨씬 길면, 문단 인덱스를 넘기지 않고 같은 문단을
        // 여러 페이지로 계속 쪼개야 한다(paginateCore의 "index는 그대로" 분기).
        val text = "가".repeat(4000)
        val paragraphs = listOf(Paragraph(text, 0, text.length))

        val pages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(widthPx = 300, heightPx = 200), maxPages = 20)

        assertEquals("아주 긴 문단 하나는 여러 페이지로 나뉘어야 함", 20, pages.size)
        // 연속된 구간이어야 한다(빈 줄이 없는 텍스트라 skipLeadingBlankLines가 아무것도 건너뛰지 않음).
        for (i in 0 until pages.size - 1) {
            assertEquals("페이지 $i 의 끝이 다음 페이지의 시작이어야 함", pages[i].endOffset, pages[i + 1].startOffset)
        }
        assertEquals(0, pages.first().startOffset)
        // 매 페이지가 실제로 앞으로 나아가야 한다(무한루프/제자리걸음 방지).
        pages.forEach { assertTrue("페이지는 항상 진전이 있어야 함(${it})", it.endOffset > it.startOffset) }
    }

    @Test
    fun extremelySmallViewport_stillMakesProgressWithoutHanging() {
        // contentHeightPx가 한 줄보다도 작으면 fitLines가 0이 될 수 있는데, Paginator는 무한루프를
        // 막기 위해 최소 1줄은 강제로 포함시킨다(fitLines == 0 -> 1 폴백). 이 폴백이 실제로 매 페이지
        // 진전을 만드는지 확인한다.
        val text = "가".repeat(500)
        val paragraphs = listOf(Paragraph(text, 0, text.length))

        val pages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(widthPx = 300, heightPx = 1), maxPages = 10)

        assertEquals(10, pages.size)
        pages.forEach { assertTrue(it.endOffset > it.startOffset) }
    }

    @Test
    fun leadingBlankLinesAtAPageStart_areSkipped() {
        // 문단 구분용 빈 줄이 다음 페이지 맨 위로 넘어가면 화면 위쪽에 실제로 없는 빈 줄이 떠 보인다 —
        // 강제로 한 페이지만큼 넘친 뒤 새 페이지 시작점이 그 개행들을 건너뛰었는지 확인한다.
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
                "다음 페이지 시작이 개행 문자가 아니어야 함(오프셋 $nextPageStart)",
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
        assertTrue("다 계산 안 한 텍스트가 남아있어야 함", pages.last().endOffset < text.length)
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
        // 실사용 경로 그대로 재현: 정방향으로 페이지 몇 장을 계산해서 실제 페이지 경계 하나를 얻은 뒤,
        // 그 끝 오프셋만 가지고 onePageEndingAt으로 같은 경계를 역산할 수 있는지 확인한다(예: 검색 점프
        // 직후 "이전" 페이지를 이력 없이 추정할 때 실제로 쓰는 경로).
        val text = "가".repeat(4000)
        val paragraphs = listOf(Paragraph(text, 0, text.length))
        val forwardPages = Paginator.paginateFrom(text, paragraphs, fromOffset = 0, textMeasurer, params(widthPx = 300, heightPx = 200), maxPages = 5)
        val targetEnd = forwardPages[2].endOffset

        val reconstructed = Paginator.onePageEndingAt(text, paragraphs, endOffset = targetEnd, textMeasurer, params(widthPx = 300, heightPx = 200), referenceSpanChars = 500)

        assertEquals(targetEnd, reconstructed?.endOffset)
    }
}

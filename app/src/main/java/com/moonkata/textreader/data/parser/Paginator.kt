package com.moonkata.textreader.data.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import com.moonkata.textreader.model.PageBreak
import com.moonkata.textreader.model.Paragraph

data class PaginationParams(
    val fontFamily: FontFamily,
    val fontSizeSp: TextUnit,
    val lineHeightMultiplier: Float,
    val letterSpacingSp: TextUnit,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val textColor: Color,
)

/**
 * 페이지에 들어갈 원문 구간(fullText의 substring)을 그대로 측정해 페이지 경계를 정한다.
 * 문단을 각각 따로 측정해서 높이를 더하는 방식은 쓰지 않는다 — 실제 렌더링(ReaderPagerContent)이
 * 페이지 전체를 하나의 Text로 그리는데, 여러 줄을 개별로 측정한 높이의 합이 그 전체를 한 번에
 * 측정한 높이와 항상 같지는 않아서(줄간격 계산 차이), 문단별 합산 방식은 실제보다 더 많이 들어간다고
 * 오판해 마지막 줄(들)이 페이지 밖으로 밀려 통째로 안 보이는 경우가 있었다.
 */
object Paginator {

    /**
     * [fromOffset]부터 앞으로만 최대 [maxPages]페이지까지 계산한다.
     * 오프셋을 포함하는 문단부터 새 페이지를 시작하므로, 책 전체를 처음부터 훑지 않고도
     * 이어읽기 위치를 즉시 화면에 보여줄 수 있다 — 전체 페이지는 백그라운드에서 [paginate]로
     * 다시 계산해 교체하기 전까지의 임시 표시용.
     */
    fun paginateFrom(
        fullText: String,
        paragraphs: List<Paragraph>,
        fromOffset: Int,
        textMeasurer: TextMeasurer,
        params: PaginationParams,
        maxPages: Int,
    ): List<PageBreak> {
        if (paragraphs.isEmpty()) return emptyList()
        val startIndex = findParagraphIndex(paragraphs, fromOffset)
        val slice = paragraphs.subList(startIndex, paragraphs.size)
        val initialStart = fromOffset.coerceIn(slice.first().startOffset, slice.first().endOffset)
        return paginateCore(fullText, slice, textMeasurer, params, maxPages, initialPageStart = initialStart)
    }

    private fun paginateCore(
        fullText: String,
        paragraphs: List<Paragraph>,
        textMeasurer: TextMeasurer,
        params: PaginationParams,
        maxPages: Int,
        initialPageStart: Int? = null,
    ): List<PageBreak> {
        if (params.contentWidthPx <= 0 || params.contentHeightPx <= 0 || paragraphs.isEmpty()) return emptyList()

        val style = TextStyle(
            fontFamily = params.fontFamily,
            fontSize = params.fontSizeSp,
            lineHeight = params.fontSizeSp * params.lineHeightMultiplier,
            letterSpacing = params.letterSpacingSp,
            color = params.textColor,
        )
        val constraints = Constraints(maxWidth = params.contentWidthPx)
        val contentHeightPx = params.contentHeightPx.toFloat()

        val pageBreaks = mutableListOf<PageBreak>()
        var pageStart = skipLeadingBlankLines(fullText, initialPageStart ?: paragraphs.first().startOffset)
        var index = 0

        while (index < paragraphs.size) {
            val candidateEnd = paragraphs[index].endOffset
            if (candidateEnd <= pageStart) {
                // 이미 이 페이지 시작점보다 앞서 끝나는 문단(빈 줄 등) — 건너뛴다.
                index++
                continue
            }

            val candidateText = fullText.substring(pageStart, candidateEnd)
            val layout = textMeasurer.measure(
                text = AnnotatedString(candidateText),
                style = style,
                constraints = constraints,
            )

            if (layout.size.height.toFloat() <= contentHeightPx) {
                // 지금까지 페이지에 이 문단까지 포함해도 들어간다 — 다음 문단도 이어서 시도.
                index++
                if (index == paragraphs.size) {
                    pageBreaks += PageBreak(pageStart, candidateEnd)
                }
            } else {
                // 이 문단까지 포함하면 넘친다 — 실제로 몇 줄까지 들어가는지 계산해 그 지점에서 페이지를 닫는다.
                var fitLines = 0
                for (lineIndex in 0 until layout.lineCount) {
                    if (layout.getLineBottom(lineIndex) > contentHeightPx) break
                    fitLines = lineIndex + 1
                }
                if (fitLines == 0) fitLines = 1 // 한 줄도 안 들어가는 극단적 상황 방지(무한루프 방지)
                val splitAt = layout.getLineEnd(fitLines - 1, visibleEnd = true).coerceAtLeast(1)
                val newPageEnd = (pageStart + splitAt).coerceAtMost(candidateEnd)
                pageBreaks += PageBreak(pageStart, newPageEnd)
                if (pageBreaks.size >= maxPages) return pageBreaks
                // 빈 줄(문단 구분용 개행)이 다음 페이지 맨 위로 넘어가면 실제로는 없는 빈 줄이 화면 위쪽에
                // 떠 보인다 — 새 페이지는 항상 개행을 건너뛰고 실제 글자부터 시작하게 한다.
                pageStart = skipLeadingBlankLines(fullText, newPageEnd)
                // index는 그대로 — 같은 문단의 나머지를 다음 페이지에서 이어서 시도한다(위 스킵으로 그 사이
                // 빈 문단들은 루프 맨 위의 "candidateEnd <= pageStart" 조건에 걸려 자동으로 건너뛰어진다).
            }
        }

        return pageBreaks
    }

    /** [offset]부터 이어지는 개행 문자들을 건너뛴 위치를 반환한다 — 문단 구분용 빈 줄이 페이지 맨 위로 넘어가지 않게. */
    private fun skipLeadingBlankLines(fullText: String, offset: Int): Int {
        var i = offset
        while (i < fullText.length && (fullText[i] == '\n' || fullText[i] == '\r')) i++
        return i
    }

    private fun findParagraphIndex(paragraphs: List<Paragraph>, offset: Int): Int {
        var lo = 0
        var hi = paragraphs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (paragraphs[mid].endOffset <= offset) lo = mid + 1 else hi = mid
        }
        return lo.coerceIn(0, paragraphs.size - 1)
    }

    private const val BACKWARD_SEARCH_BATCH_PAGES = 8
    private const val BACKWARD_SEARCH_MAX_ATTEMPTS = 4
    private const val BACKWARD_SEARCH_MIN_SPAN = 500

    /**
     * "이전 페이지"를 방문 이력 없이(예: 점프 직후 첫 이전 클릭) 역산으로 추정한다.
     * [endOffset]보다 [referenceSpanChars]만큼 앞에서부터 순방향으로 몇 페이지 계산해, endOffset
     * 바로 앞에서 끝나는 페이지를 찾는다. 시작점을 잘못 추정해도(다음 페이지가 endOffset을 정확히
     * 맞히지 못해도) 상관없다 — 이후 정방향으로 한 번이라도 넘기면 방문 이력이 정확한 값으로 채워진다.
     */
    fun onePageEndingAt(
        fullText: String,
        paragraphs: List<Paragraph>,
        endOffset: Int,
        textMeasurer: TextMeasurer,
        params: PaginationParams,
        referenceSpanChars: Int,
    ): PageBreak? {
        if (endOffset <= 0 || paragraphs.isEmpty()) return null
        var span = referenceSpanChars.coerceAtLeast(BACKWARD_SEARCH_MIN_SPAN)
        repeat(BACKWARD_SEARCH_MAX_ATTEMPTS) {
            val candidateStart = (endOffset - span).coerceAtLeast(0)
            val forward = paginateFrom(fullText, paragraphs, candidateStart, textMeasurer, params, maxPages = BACKWARD_SEARCH_BATCH_PAGES)
            val match = forward.lastOrNull { it.endOffset <= endOffset }
            if (match != null && (match.endOffset == endOffset || candidateStart == 0)) return match
            if (match != null && forward.last() != match) return match // endOffset 바로 앞에서 끊긴 페이지를 찾음
            if (candidateStart == 0) return match ?: forward.firstOrNull()
            span *= 2 // 그 사이에 못 미쳤다 — 시작점을 더 앞으로 넓혀 재시도
        }
        return null
    }
}

package com.moonkata.textreader.data.parser

import com.moonkata.textreader.model.Chapter

/**
 * 챕터 점프 모드: 각 챕터 구간을 N등분한 지점(breakpoint)들을 계산한다.
 * i=N번째 지점은 정확히 다음 챕터의 시작 오프셋과 같아, "다음"을 계속 누르면
 * 1/N, 2/N, ..., (N-1)/N 지점을 거쳐 자연스럽게 다음 챕터로 넘어간다.
 */
object ChapterJumpNavigator {

    /** 챕터 패턴 두 개가 이보다 적은 줄 수 안에 붙어 있으면(예: "추신: ..." 공지문 한두 줄 뒤 바로
     * 진짜 챕터 제목) 그 사이를 등분하지 않는다 — .docs/IDEAS.md 예시 참고. */
    private const val CLOSE_CHAPTER_LINE_THRESHOLD = 20

    /**
     * @param text 줄 수 계산용 원문 전체(길이는 [totalCharCount]와 같아야 함). 다음 챕터 패턴이 이
     * 챕터로부터 [CLOSE_CHAPTER_LINE_THRESHOLD]줄 이내면 그 구간은 등분하지 않고 챕터에서 챕터로
     * 곧장 건너뛴다(공지성 한 줄짜리 "챕터"에서 여러 번 다음을 눌러야 빠져나가는 문제 방지).
     */
    fun breakpoints(chapters: List<Chapter>, totalCharCount: Int, divisions: Int, text: String): List<Int> {
        if (chapters.isEmpty() || divisions < 1 || totalCharCount <= 0) return emptyList()
        val sorted = chapters.sortedBy { it.charOffset }
        val result = mutableListOf<Int>()
        for (idx in sorted.indices) {
            val start = sorted[idx].charOffset
            val hasNextChapter = idx + 1 < sorted.size
            val end = if (hasNextChapter) sorted[idx + 1].charOffset else totalCharCount
            val length = end - start
            if (length <= 0) continue
            if (hasNextChapter && lineCountBetween(text, start, end) <= CLOSE_CHAPTER_LINE_THRESHOLD) {
                result += end.coerceAtMost(totalCharCount)
                continue
            }
            for (i in 1..divisions) {
                val point = start + ((length.toLong() * i) / divisions).toInt()
                result += point.coerceAtMost(totalCharCount)
            }
        }
        return result.distinct().sorted()
    }

    private fun lineCountBetween(text: String, start: Int, end: Int): Int {
        var count = 0
        val safeEnd = end.coerceAtMost(text.length)
        for (i in start until safeEnd) {
            if (text[i] == '\n') count++
        }
        return count
    }

    fun nextBreakpoint(breakpoints: List<Int>, currentOffset: Int): Int? =
        breakpoints.firstOrNull { it > currentOffset }

    fun previousBreakpoint(breakpoints: List<Int>, currentOffset: Int): Int? =
        breakpoints.lastOrNull { it < currentOffset }
}

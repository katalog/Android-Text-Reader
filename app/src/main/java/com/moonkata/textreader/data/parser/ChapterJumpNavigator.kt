package com.moonkata.textreader.data.parser

import com.moonkata.textreader.model.Chapter

/**
 * Chapter jump mode: computes the breakpoints that divide each chapter's span into N equal parts.
 * The i=N-th point is exactly the next chapter's start offset, so repeatedly pressing "next"
 * walks through 1/N, 2/N, ..., (N-1)/N and then naturally lands on the next chapter.
 */
object ChapterJumpNavigator {

    /** If two chapter patterns are separated by fewer lines than this (e.g. a one- or two-line
     * "P.S.: ..." notice immediately followed by the real chapter title), don't subdivide that
     * span — see the example in .docs/IDEAS.md. */
    private const val CLOSE_CHAPTER_LINE_THRESHOLD = 20

    /**
     * @param text Full source text, used to count lines (its length must equal [totalCharCount]).
     * If the next chapter pattern is within [CLOSE_CHAPTER_LINE_THRESHOLD] lines of this chapter,
     * that span is not subdivided — it jumps straight from chapter to chapter (prevents having to
     * press "next" repeatedly to escape a one-line notice-style "chapter").
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

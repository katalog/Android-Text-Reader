package com.moonkata.textreader.data.parser

import com.moonkata.textreader.model.Chapter

/**
 * 챕터 점프 모드: 각 챕터 구간을 N등분한 지점(breakpoint)들을 계산한다.
 * i=N번째 지점은 정확히 다음 챕터의 시작 오프셋과 같아, "다음"을 계속 누르면
 * 1/N, 2/N, ..., (N-1)/N 지점을 거쳐 자연스럽게 다음 챕터로 넘어간다.
 */
object ChapterJumpNavigator {

    fun breakpoints(chapters: List<Chapter>, totalCharCount: Int, divisions: Int): List<Int> {
        if (chapters.isEmpty() || divisions < 1 || totalCharCount <= 0) return emptyList()
        val sorted = chapters.sortedBy { it.charOffset }
        val result = mutableListOf<Int>()
        for (idx in sorted.indices) {
            val start = sorted[idx].charOffset
            val end = if (idx + 1 < sorted.size) sorted[idx + 1].charOffset else totalCharCount
            val length = end - start
            if (length <= 0) continue
            for (i in 1..divisions) {
                val point = start + ((length.toLong() * i) / divisions).toInt()
                result += point.coerceAtMost(totalCharCount)
            }
        }
        return result.distinct().sorted()
    }

    fun nextBreakpoint(breakpoints: List<Int>, currentOffset: Int): Int? =
        breakpoints.firstOrNull { it > currentOffset }

    fun previousBreakpoint(breakpoints: List<Int>, currentOffset: Int): Int? =
        breakpoints.lastOrNull { it < currentOffset }
}

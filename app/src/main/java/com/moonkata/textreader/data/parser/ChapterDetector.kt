package com.moonkata.textreader.data.parser

import com.moonkata.textreader.model.Chapter

object ChapterDetector {

    /** 매칭 0건은 정상("목차 없음") 상태이지 에러가 아니다. */
    fun detect(text: String, patterns: List<Regex>): List<Chapter> {
        if (patterns.isEmpty()) return emptyList()
        val chapters = mutableListOf<Chapter>()
        val n = text.length
        var start = 0
        var i = 0
        while (i <= n) {
            if (i == n || text[i] == '\n') {
                var end = i
                if (end > start && text[end - 1] == '\r') end -= 1
                val line = text.substring(start, end).trim()
                if (line.isNotEmpty() && line.length <= 60 && patterns.any { it.matches(line) }) {
                    chapters += Chapter(line, start)
                }
                start = i + 1
            }
            i++
        }
        return chapters
    }
}

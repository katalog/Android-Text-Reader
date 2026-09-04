package com.moonkata.textreader.data.parser

import com.moonkata.textreader.model.Chapter

object ChapterDetector {

    /** Zero matches is a normal ("no table of contents") state, not an error. */
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

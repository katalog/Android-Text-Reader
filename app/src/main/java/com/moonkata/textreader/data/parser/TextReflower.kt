package com.moonkata.textreader.data.parser

import com.moonkata.textreader.data.datastore.LineBreakMode
import com.moonkata.textreader.model.Paragraph

object TextReflower {

    fun reflow(text: String, mode: LineBreakMode): List<Paragraph> {
        return when (mode) {
            LineBreakMode.PRESERVE -> preserveLines(text)
            LineBreakMode.REFLOW -> reflowParagraphs(text)
        }
    }

    /** 원문 줄바꿈을 그대로 유지 — 매 줄이 하나의 문단이 된다. */
    private fun preserveLines(text: String): List<Paragraph> {
        val paragraphs = mutableListOf<Paragraph>()
        val n = text.length
        var start = 0
        var i = 0
        while (i <= n) {
            if (i == n || text[i] == '\n') {
                var end = i
                if (end > start && text[end - 1] == '\r') end -= 1
                paragraphs += Paragraph(text.substring(start, end), start, end)
                start = i + 1
            }
            i++
        }
        return paragraphs
    }

    /** 빈 줄(연속 개행) = 문단 경계, 단일 개행은 공백으로 이어붙임. */
    private fun reflowParagraphs(text: String): List<Paragraph> {
        val paragraphs = mutableListOf<Paragraph>()
        val n = text.length
        var i = 0
        var paraStart = -1
        val builder = StringBuilder()

        fun flush(end: Int) {
            if (paraStart >= 0 && builder.isNotEmpty()) {
                paragraphs += Paragraph(builder.toString(), paraStart, end)
            }
            builder.clear()
            paraStart = -1
        }

        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                var j = i
                var newlineCount = 0
                while (j < n && (text[j] == '\n' || text[j] == '\r')) {
                    if (text[j] == '\n') newlineCount++
                    j++
                }
                if (newlineCount >= 2) {
                    flush(i)
                } else if (builder.isNotEmpty() && !builder.endsWith(' ')) {
                    builder.append(' ')
                }
                i = j
                continue
            } else {
                if (paraStart < 0) paraStart = i
                builder.append(c)
                i++
            }
        }
        flush(n)
        return paragraphs
    }
}

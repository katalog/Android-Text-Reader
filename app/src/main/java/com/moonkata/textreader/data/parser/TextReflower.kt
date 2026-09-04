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

    /** Keeps the original line breaks as-is — each line becomes one paragraph. */
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

    /** A blank line (consecutive newlines) marks a paragraph boundary; a single newline is joined with a space. */
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

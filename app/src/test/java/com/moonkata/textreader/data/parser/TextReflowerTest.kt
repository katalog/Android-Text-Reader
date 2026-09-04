package com.moonkata.textreader.data.parser

import com.moonkata.textreader.data.datastore.LineBreakMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the paragraph-splitting (PRESERVE/REFLOW) logic. Pagination consumes the Paragraph
 * list produced here as-is, so a bug here immediately throws off the entire page calculation.
 * Has no Android dependency, so this is a plain JUnit test.
 */
class TextReflowerTest {

    // --- PRESERVE: every line becomes its own paragraph as-is ---

    @Test
    fun preserve_basicMultilineSplit() {
        val text = "첫줄\n둘째줄\n셋째줄"

        val paragraphs = TextReflower.reflow(text, LineBreakMode.PRESERVE)

        assertEquals(listOf("첫줄", "둘째줄", "셋째줄"), paragraphs.map { it.text })
        assertEquals(listOf(0 to 2, 3 to 6, 7 to 10), paragraphs.map { it.startOffset to it.endOffset })
    }

    @Test
    fun preserve_crlfLineEndings_stripTrailingCr() {
        val text = "첫줄\r\n둘째줄\r\n"

        val paragraphs = TextReflower.reflow(text, LineBreakMode.PRESERVE)

        // Since every line is its own paragraph, one extra trailing newline produces one extra
        // empty paragraph at the end (because the i==n position is also always treated as a
        // paragraph boundary — this is intentional).
        assertEquals(listOf("첫줄", "둘째줄", ""), paragraphs.map { it.text })
        assertEquals(listOf(0 to 2, 4 to 7, 9 to 9), paragraphs.map { it.startOffset to it.endOffset })
    }

    @Test
    fun preserve_blankLineBecomesItsOwnEmptyParagraph() {
        val text = "첫줄\n\n셋째줄\n"

        val paragraphs = TextReflower.reflow(text, LineBreakMode.PRESERVE)

        // Unlike REFLOW, a blank line is not merged away — it becomes its own empty paragraph
        // (keeping a strict 1:1 line-to-paragraph mapping).
        assertEquals(listOf("첫줄", "", "셋째줄", ""), paragraphs.map { it.text })
    }

    @Test
    fun preserve_emptyInput_producesOneEmptyParagraph() {
        val paragraphs = TextReflower.reflow("", LineBreakMode.PRESERVE)

        assertEquals(listOf(""), paragraphs.map { it.text })
        assertEquals(0, paragraphs[0].startOffset)
        assertEquals(0, paragraphs[0].endOffset)
    }

    // --- REFLOW: a blank line (consecutive newlines) = paragraph boundary, a single newline = joined with a space ---

    @Test
    fun reflow_singleNewline_joinsLinesWithASpace() {
        val text = "이것은\n한 줄로\n합쳐진다"

        val paragraphs = TextReflower.reflow(text, LineBreakMode.REFLOW)

        assertEquals(listOf("이것은 한 줄로 합쳐진다"), paragraphs.map { it.text })
        assertEquals(0, paragraphs[0].startOffset)
        assertEquals(text.length, paragraphs[0].endOffset)
    }

    @Test
    fun reflow_doubleNewline_startsANewParagraph() {
        val text = "첫 문단\n\n둘째 문단"

        val paragraphs = TextReflower.reflow(text, LineBreakMode.REFLOW)

        assertEquals(listOf("첫 문단", "둘째 문단"), paragraphs.map { it.text })
        assertEquals(listOf(0 to 4, 6 to 11), paragraphs.map { it.startOffset to it.endOffset })
    }

    @Test
    fun reflow_crlfBlankLine_isStillCountedAsAParagraphBreak() {
        val text = "첫 문단\r\n\r\n둘째 문단"

        val paragraphs = TextReflower.reflow(text, LineBreakMode.REFLOW)

        assertEquals(listOf("첫 문단", "둘째 문단"), paragraphs.map { it.text })
        assertEquals(listOf(0 to 4, 8 to 13), paragraphs.map { it.startOffset to it.endOffset })
    }

    @Test
    fun reflow_manyConsecutiveBlankLines_collapseToASingleBreak() {
        val text = "A\n\n\n\nB"

        val paragraphs = TextReflower.reflow(text, LineBreakMode.REFLOW)

        // No matter how many blank lines there are, it's still a single paragraph boundary —
        // no empty paragraphs should be inserted in between.
        assertEquals(listOf("A", "B"), paragraphs.map { it.text })
    }

    @Test
    fun reflow_leadingAndTrailingBlankLines_produceNoPhantomParagraphs() {
        val text = "\n\n본문\n\n"

        val paragraphs = TextReflower.reflow(text, LineBreakMode.REFLOW)

        assertEquals(listOf("본문"), paragraphs.map { it.text })
    }

    @Test
    fun reflow_emptyInput_producesNoParagraphs() {
        val paragraphs = TextReflower.reflow("", LineBreakMode.REFLOW)

        assertEquals(emptyList<String>(), paragraphs.map { it.text })
    }
}

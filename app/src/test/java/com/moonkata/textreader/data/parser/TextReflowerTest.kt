package com.moonkata.textreader.data.parser

import com.moonkata.textreader.data.datastore.LineBreakMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 문단화(PRESERVE/REFLOW) 로직 검증. 여기서 만든 Paragraph 목록을 페이지네이션이 그대로 쓰기 때문에,
 * 여기 버그는 곧바로 페이지 계산 전체를 틀어지게 한다. Android 의존성이 없어 일반 JUnit으로 둔다.
 */
class TextReflowerTest {

    // --- PRESERVE: 매 줄이 그대로 하나의 문단 ---

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

        // 매 줄이 하나의 문단이라, 끝에 개행이 하나 더 있으면 마지막에 빈 문단이 하나 더 생긴다
        // (i==n 지점도 항상 문단 경계로 처리하기 때문 — 의도된 동작).
        assertEquals(listOf("첫줄", "둘째줄", ""), paragraphs.map { it.text })
        assertEquals(listOf(0 to 2, 4 to 7, 9 to 9), paragraphs.map { it.startOffset to it.endOffset })
    }

    @Test
    fun preserve_blankLineBecomesItsOwnEmptyParagraph() {
        val text = "첫줄\n\n셋째줄\n"

        val paragraphs = TextReflower.reflow(text, LineBreakMode.PRESERVE)

        // REFLOW와 달리 빈 줄도 병합하지 않고 그 자체로 빈 문단이 된다(줄=문단 1:1 유지).
        assertEquals(listOf("첫줄", "", "셋째줄", ""), paragraphs.map { it.text })
    }

    @Test
    fun preserve_emptyInput_producesOneEmptyParagraph() {
        val paragraphs = TextReflower.reflow("", LineBreakMode.PRESERVE)

        assertEquals(listOf(""), paragraphs.map { it.text })
        assertEquals(0, paragraphs[0].startOffset)
        assertEquals(0, paragraphs[0].endOffset)
    }

    // --- REFLOW: 빈 줄(연속 개행)=문단 경계, 단일 개행=공백으로 이어붙임 ---

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

        // 빈 줄이 몇 개든 문단 경계는 한 번이지, 그 사이에 빈 문단들이 끼어들면 안 된다.
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

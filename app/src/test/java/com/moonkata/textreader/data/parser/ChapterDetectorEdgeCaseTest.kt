package com.moonkata.textreader.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChapterDetector/ChapterPatternCatalog의 세부 분기(줄바꿈 처리, 길이 제한, 커스텀 정규식 오류 처리,
 * 오프셋 계산 등)를 합성 문자열로 검증한다. Android 의존성이 없어 일반 JUnit 테스트로 둔다 — 실제
 * 픽스처 소설로 하는 정탐/오탐 없음 검증은 androidTest의 ChapterDetectionRegressionTest 몫이다.
 */
class ChapterDetectorEdgeCaseTest {

    private val hashPattern = listOf(Regex("""^##.*$"""))

    @Test
    fun emptyPatternList_detectsNoChaptersEvenIfLinesWouldMatch() {
        val text = "## 챕터 1\n본문\n## 챕터 2\n"

        assertTrue(ChapterDetector.detect(text, emptyList()).isEmpty())
    }

    @Test
    fun crlfLineEndings_areHandledCorrectly() {
        val text = "## 챕터 1\r\n본문\r\n## 챕터 2\r\n"

        val chapters = ChapterDetector.detect(text, hashPattern)

        assertEquals(listOf("## 챕터 1", "## 챕터 2"), chapters.map { it.title })
        assertEquals(0, chapters[0].charOffset)
        assertEquals(text.indexOf("## 챕터 2"), chapters[1].charOffset)
    }

    @Test
    fun lastLineWithoutTrailingNewline_isStillDetected() {
        val text = "본문\n## 마지막 챕터"

        val chapters = ChapterDetector.detect(text, hashPattern)

        assertEquals(listOf("## 마지막 챕터"), chapters.map { it.title })
        assertEquals(text.indexOf("## 마지막"), chapters[0].charOffset)
    }

    @Test
    fun linesLongerThan60Chars_areExcludedEvenIfPatternMatches() {
        val tooLong = "## " + "가".repeat(60) // trim 후 63자 - 제한(60) 초과
        val text = "$tooLong\n## 짧은 챕터\n"

        val chapters = ChapterDetector.detect(text, hashPattern)

        assertEquals(listOf("## 짧은 챕터"), chapters.map { it.title })
    }

    @Test
    fun charOffsetPointsToTheRawLineStart_notTheTrimmedTitleStart() {
        // "본문\n" 다음 줄이 앞에 공백이 있는 챕터 제목 — charOffset은 trim되기 전, 공백을 포함한
        // 원래 줄의 시작 지점(=이전 줄바꿈 바로 다음)이어야 한다.
        val text = "본문\n   ## 앞에 공백 있는 챕터   \n"

        val chapters = ChapterDetector.detect(text, hashPattern)

        assertEquals(1, chapters.size)
        assertEquals("## 앞에 공백 있는 챕터", chapters[0].title)
        assertEquals(3, chapters[0].charOffset)
    }

    @Test
    fun invalidCustomRegex_isSilentlyDroppedFromTheBuiltList() {
        val patterns = ChapterPatternCatalog.buildRegexList(
            enabledIds = emptySet(),
            customPatterns = setOf("[invalid(regex", """^Chapter \d+$"""),
        )
        assertEquals("잘못된 정규식은 조용히 걸러지고 유효한 것만 남아야 함", 1, patterns.size)

        val chapters = ChapterDetector.detect("Chapter 5\n본문\n", patterns)
        assertEquals(listOf("Chapter 5"), chapters.map { it.title })
    }

    @Test
    fun overlappingBuiltinAndCustomPattern_countsTheSameLineOnce() {
        val patterns = ChapterPatternCatalog.buildRegexList(
            enabledIds = ChapterPatternCatalog.defaultEnabledIds,
            customPatterns = setOf("""^## Chapter.*$"""),
        )

        val chapters = ChapterDetector.detect("## Chapter 1\n본문\n", patterns)

        assertEquals("두 패턴에 동시에 매칭되는 줄도 챕터 하나로만 잡혀야 함", 1, chapters.size)
    }

    @Test
    fun consecutiveChapterHeadings_eachDetectedWithCorrectOffsets() {
        val text = "## 1장\n## 2장\n## 3장\n"

        val chapters = ChapterDetector.detect(text, hashPattern)

        assertEquals(listOf("## 1장", "## 2장", "## 3장"), chapters.map { it.title })
        assertEquals(listOf(0, 6, 12), chapters.map { it.charOffset })
    }

    @Test
    fun defaultEnabledIds_includesEveryBuiltinPreset() {
        assertEquals(ChapterPatternCatalog.presets.map { it.id }.toSet(), ChapterPatternCatalog.defaultEnabledIds)
    }
}

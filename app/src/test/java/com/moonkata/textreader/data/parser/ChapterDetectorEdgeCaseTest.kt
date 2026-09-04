package com.moonkata.textreader.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies detailed branches of ChapterDetector/ChapterPatternCatalog (line-break handling,
 * length limits, custom regex error handling, offset calculation, etc.) using synthetic strings.
 * Has no Android dependency, so this is a plain JUnit test — verifying true/false positives
 * against real fixture novels is androidTest's ChapterDetectionRegressionTest's job.
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
        val tooLong = "## " + "가".repeat(60) // 63 chars after trim - exceeds the limit (60)
        val text = "$tooLong\n## 짧은 챕터\n"

        val chapters = ChapterDetector.detect(text, hashPattern)

        assertEquals(listOf("## 짧은 챕터"), chapters.map { it.title })
    }

    @Test
    fun charOffsetPointsToTheRawLineStart_notTheTrimmedTitleStart() {
        // The line after "본문\n" is a chapter title with leading whitespace — charOffset should
        // point to the start of the raw line (including the whitespace) before trimming, i.e.
        // right after the previous newline.
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
        assertEquals("An invalid regex should be silently filtered out, leaving only valid ones", 1, patterns.size)

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

        assertEquals("A line matching both patterns at once should still be counted as a single chapter", 1, chapters.size)
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

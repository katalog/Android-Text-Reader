package com.moonkata.textreader.data.parser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.testutil.TestBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies automatic chapter detection has no false positives/negatives, using real fixtures
 * (public-domain novels by Lee Kwang-su). Zero matches is not an error but the normal "no table
 * of contents" state — Mujeong.txt (무정, 1917) is a good example of this case since it's
 * continuous prose with no `##` headers at all. Heuk.txt (흙, 1932) is a version that preserves the
 * original chapter breaks (Chapter 1 through Chapter 5) from the Wikisource text, with a
 * `## 제N장` header inserted at the start of each chapter — used as the true-positive case.
 */
@RunWith(AndroidJUnit4::class)
class ChapterDetectionRegressionTest {

    @Test
    fun detectsHashPrefixedChaptersOnARealFixture() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val text = TestBooks.copyToCache(application, bookAsset).readText()

        val patterns = ChapterPatternCatalog.buildRegexList(ChapterPatternCatalog.defaultEnabledIds, emptySet())
        val chapters = ChapterDetector.detect(text, patterns)

        assertEquals("Chapters starting with \"## \" should be detected, matching the source's chapter count (1 through 5)", 5, chapters.size)
        assertEquals("The first chapter's title should match the actual text", "## 제1장", chapters.first().title)
        assertEquals("The first chapter's offset should match the very start of the text", 0, chapters.first().charOffset)
    }

    @Test
    fun noHashPrefix_correctlyDetectsNoChapters() {
        val bookAsset = "Mujeong.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val text = TestBooks.copyToCache(application, bookAsset).readText()

        val patterns = ChapterPatternCatalog.buildRegexList(ChapterPatternCatalog.defaultEnabledIds, emptySet())
        val chapters = ChapterDetector.detect(text, patterns)

        assertTrue(
            "A continuous-prose fixture with no \"##\" headers should detect zero chapters under the default preset (expected)",
            chapters.isEmpty(),
        )
    }
}

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
 * 챕터 자동 인식의 정탐/오탐 없음을 실제 픽스처로 검증한다. 매칭 0건은 에러가 아니라 "목차 없음"
 * 정상 상태 — Yellow Radio.txt가 `##` 없이 `제N장`만 쓰는 유일한 픽스처라 이 케이스의 좋은 예시.
 */
@RunWith(AndroidJUnit4::class)
class ChapterDetectionRegressionTest {

    @Test
    fun detectsHashPrefixedChaptersOnARealFixture() {
        val bookAsset = "Static.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val text = TestBooks.copyToCache(application, bookAsset).readText()

        val patterns = ChapterPatternCatalog.buildRegexList(ChapterPatternCatalog.defaultEnabledIds, emptySet())
        val chapters = ChapterDetector.detect(text, patterns)

        assertTrue("\"## \"로 시작하는 챕터가 여러 개 잡혀야 함", chapters.size > 50)
        assertEquals("첫 챕터 제목이 실제 본문과 일치해야 함", "## ■ 제1장 시라키 쇼(白木承)", chapters.first().title)
        assertEquals("첫 챕터 오프셋은 본문 시작(앞의 빈 줄 두 개 다음)과 일치해야 함", 2, chapters.first().charOffset)
    }

    @Test
    fun noHashPrefix_correctlyDetectsNoChapters() {
        val bookAsset = "Yellow Radio.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val text = TestBooks.copyToCache(application, bookAsset).readText()

        val patterns = ChapterPatternCatalog.buildRegexList(ChapterPatternCatalog.defaultEnabledIds, emptySet())
        val chapters = ChapterDetector.detect(text, patterns)

        assertTrue(
            "\"##\" 프리픽스가 없는 \"제N장\"만 있는 픽스처는 기본 프리셋으로 챕터가 하나도 안 잡혀야 함(정상)",
            chapters.isEmpty(),
        )
    }
}

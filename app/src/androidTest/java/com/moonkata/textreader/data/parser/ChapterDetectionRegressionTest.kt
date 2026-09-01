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
 * 챕터 자동 인식의 정탐/오탐 없음을 실제 픽스처(퍼블릭 도메인 소설, 이광수 저)로 검증한다. 매칭 0건은
 * 에러가 아니라 "목차 없음" 정상 상태 — Mujeong.txt(무정, 1917)가 `##` 헤더가 전혀 없는 연속된 산문
 * 원문이라 이 케이스의 좋은 예시. Heuk.txt(흙, 1932)는 위키문헌 원문의 실제 장 구분(제1장~제5장)을
 * 그대로 살려 각 장 시작에 `## 제N장` 헤더를 넣어둔 버전 — 정탐 케이스로 쓴다.
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

        assertEquals("\"## \"로 시작하는 챕터가 원문의 장(제1장~제5장) 수만큼 잡혀야 함", 5, chapters.size)
        assertEquals("첫 챕터 제목이 실제 본문과 일치해야 함", "## 제1장", chapters.first().title)
        assertEquals("첫 챕터 오프셋은 본문 맨 처음과 일치해야 함", 0, chapters.first().charOffset)
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
            "\"##\" 헤더가 없는 연속 산문 픽스처는 기본 프리셋으로 챕터가 하나도 안 잡혀야 함(정상)",
            chapters.isEmpty(),
        )
    }
}

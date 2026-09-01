package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.TestTextMeasurer
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 검색결과/목차/챕터점프로 도착하는 실제 경로 — jumpToOffset이 목표 오프셋에서 시작하는 페이지를
 * 곧바로 계산해 보여주는지, 실제 소설의 뒷부분(시작 위치와 확실히 먼 지점)으로 검증한다.
 * 예전 구조(책 전체 페이지 리스트)에서는 아직 계산 안 된 먼 지점으로 점프하면 계산된 범위의
 * 가장 가까운 끝으로 클램프되는 버그가 있었는데, 지금 구조는 점프할 때마다 그 지점부터 바로
 * 한 페이지를 계산하므로 그 버그 자체가 구조적으로 재발할 수 없다 — 그래도 회귀 방지용으로 남겨둔다.
 */
@RunWith(AndroidJUnit4::class)
class JumpToFarOffsetTest {

    @Test
    fun jumpingFarIntoTheBook_landsOnAPageContainingTheTargetText() {
        TestBooks.assumeAvailable(BOOK_ASSET)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, BOOK_ASSET) }

        // 실기기의 실제 DataStore를 공유하므로, jumpToOffset이 페이지 모드 경로(jumpToPageAt)를 타도록
        // pageTurnMode를 강제로 고정해둔다 — 세로 스크롤 모드로 남아있으면 currentPage가 아예 갱신되지
        // 않아 테스트가 타임아웃으로 실패한다. 끝나면 원래 값으로 되돌린다.
        val originalPageTurnMode = runBlocking { settingsRepository.settingsFlow.first() }.pageTurnMode
        runBlocking { settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE) }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            waitUntilTrue { viewModel.uiState.value.settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE }

            viewModel.onViewportMeasured(TestTextMeasurer.create(application), PageNavigationRoundTripTest.testParams())
            waitUntilTrue { viewModel.uiState.value.currentPage != null }

            val fullText = viewModel.uiState.value.fullText
            val halfway = fullText.length / 2
            val marker = "## 제5장"
            val targetOffset = fullText.indexOf(marker, startIndex = halfway)
            check(targetOffset >= 0) { "테스트용 마커(\"$marker\")를 책 후반부에서 못 찾음 — 픽스처가 바뀌었을 수 있음" }

            viewModel.jumpToOffset(targetOffset)
            waitUntilTrue { viewModel.uiState.value.currentPage?.startOffset ?: -1 >= halfway }

            val page = viewModel.uiState.value.currentPage!!
            val pageText = fullText.substring(page.startOffset, page.endOffset)
            assertTrue(
                "점프한 페이지에 목표 지점의 텍스트(\"$marker\")가 보여야 함",
                pageText.contains(marker),
            )
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalPageTurnMode)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    companion object {
        private const val BOOK_ASSET = "Heuk.txt"
    }
}

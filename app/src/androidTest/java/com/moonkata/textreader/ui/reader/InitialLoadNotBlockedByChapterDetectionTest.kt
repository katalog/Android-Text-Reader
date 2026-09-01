package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.model.Chapter
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.TestTextMeasurer
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 챕터 탐지(전체 텍스트 줄 단위 정규식 스캔, 대용량 소설일수록 오래 걸림)가 첫 페이지 표시를 막지
 * 않는지 실제 대용량 픽스처(`Heuk.txt`, 5개 챕터)로 검증한다. `loadBook()`이 다시 챕터
 * 탐지까지 끝나야 `isLoading`을 끄는 예전 방식으로 회귀하면, 그 순간 챕터가 이미 다 채워져 있게 되어
 * 이 테스트가 실패한다.
 */
@RunWith(AndroidJUnit4::class)
class InitialLoadNotBlockedByChapterDetectionTest {

    @Test
    fun loadingFinishes_andPageTurnsWork_beforeChapterDetectionCompletes() {
        TestBooks.assumeAvailable(BOOK_ASSET)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, BOOK_ASSET) }

        // PageNavigationRoundTripTest와 같은 이유로 프로덕션 DataStore에 남은 설정을 고정/복원한다.
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            settingsRepository.updateChapterJumpEnabled(false)
        }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)

            // isLoading이 꺼지는 순간을 촘촘하게(2ms 간격) 폴링해서 그 시점의 chapters 상태를 같이
            // 붙잡는다 — 다른 테스트에서 쓰는 waitUntilTrue의 기본 50ms 간격으로는 이 짧은 전환
            // 구간을 지나쳐서 이미 채워진 chapters를 보게 될 수 있어 더 촘촘한 간격을 쓴다.
            var chaptersWhenLoadingFinished: List<Chapter>? = null
            waitUntilTrue(timeoutMs = 10_000, intervalMs = 2) {
                val state = viewModel.uiState.value
                if (!state.isLoading && chaptersWhenLoadingFinished == null) {
                    chaptersWhenLoadingFinished = state.chapters
                }
                !state.isLoading
            }

            assertTrue(
                "로딩이 끝난 시점엔 페이지 표시에 필요한 문단만 준비되면 되고, 챕터 탐지(느린 전체 텍스트 " +
                    "정규식 스캔)까지 끝날 필요는 없다 — 이 시점에 챕터가 이미 채워져 있다면 챕터 탐지가 " +
                    "다시 로딩을 막고 있다는 뜻(회귀)",
                chaptersWhenLoadingFinished?.isEmpty() ?: true,
            )
            assertTrue("로딩이 끝나면 페이지 표시에 필요한 문단은 준비돼 있어야 함", viewModel.uiState.value.paragraphs.isNotEmpty())

            // 챕터 탐지를 기다리지 않고, 로딩이 끝나자마자 페이지 전환이 실제로 되는지 확인.
            viewModel.onViewportMeasured(TestTextMeasurer.create(application), PageNavigationRoundTripTest.testParams())
            waitUntilTrue { viewModel.uiState.value.currentPage != null }
            val startPage = viewModel.uiState.value.currentPage

            viewModel.next()
            waitUntilTrue { viewModel.uiState.value.currentPage != startPage }
            assertNotEquals("첫 페이지 계산 직후 next()가 실제로 페이지를 전진시켜야 함", startPage, viewModel.uiState.value.currentPage)

            // 챕터 탐지는 백그라운드에서 계속 진행되어 나중에 정상적으로 채워져야 한다(회귀 방지).
            waitUntilTrue(timeoutMs = 10_000) { viewModel.uiState.value.chapters.isNotEmpty() }
            assertEquals(
                "\"## \"로 시작하는 챕터가 원문의 장 수만큼 잡혀야 함(ChapterDetectionRegressionTest와 동일 기대치)",
                5,
                viewModel.uiState.value.chapters.size,
            )
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateChapterJumpEnabled(originalSettings.chapterJumpEnabled)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    companion object {
        private const val BOOK_ASSET = "Heuk.txt"
    }
}

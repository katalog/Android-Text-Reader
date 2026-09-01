package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.parser.PaginationParams
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.TestTextMeasurer
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 페이지 모드 재설계(전역 페이지 리스트 없이 currentPage 하나만 그때그때 계산)의 핵심 계약을 실제
 * 대용량 소설로 검증한다: 정방향으로 N번 넘긴 뒤 역방향으로 N번 넘기면, 방문 이력 스택 덕분에 다시
 * 계산하지 않고도 시작 페이지와 정확히 같은 곳으로 돌아와야 한다.
 *
 * 화면 렌더링 없이 ReaderViewModel을 직접 구동한다 — 페이지 이동이 더 이상 Compose Pager/이벤트에
 * 의존하지 않아서, 이 계약은 뷰모델만으로 완전히 검증 가능하다.
 */
@RunWith(AndroidJUnit4::class)
class PageNavigationRoundTripTest {

    @Test
    fun advancingForwardThenBackward_returnsToTheExactSamePage() {
        TestBooks.assumeAvailable(BOOK_ASSET)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, BOOK_ASSET) }

        // 이 테스트는 실제 앱과 같은 DataStore를 쓴다 — 실기기에 남아있던 다른 설정(세로 스크롤
        // 모드, 자동 타이머 넘김 켜짐 등)이 섞이면 next()/previous()가 페이지 모드로 안 가거나,
        // 타이머가 백그라운드에서 몰래 next()를 더 호출해 방문 이력 스택이 테스트가 센 횟수와
        // 어긋난다 — 그래서 이 테스트에 필요한 설정만 강제로 고정하고, 끝나면 원래 값으로 되돌린다.
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            // chapterJumpEnabled가 켜져 있으면 next()/previous()가 방문 이력 스택이 아니라
            // ChapterJumpNavigator의 목차 등분 breakpoint를 타서, 이 테스트가 검증하려는
            // "페이지 단위 방문 이력" 계약과 전혀 다른 이동 방식이 돼버린다.
            settingsRepository.updateChapterJumpEnabled(false)
        }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            waitUntilTrue {
                val settings = viewModel.uiState.value.settings
                settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE && !settings.chapterJumpEnabled
            }

            viewModel.onViewportMeasured(TestTextMeasurer.create(application), testParams())
            waitUntilTrue { viewModel.uiState.value.currentPage != null }

            val startPage = viewModel.uiState.value.currentPage
            assertNotNull(startPage)

            val steps = 15
            repeat(steps) {
                val before = viewModel.uiState.value.currentPage
                viewModel.next()
                waitUntilTrue { viewModel.uiState.value.currentPage != before }
            }

            val farPage = viewModel.uiState.value.currentPage
            assertNotEquals("여러 페이지 전진했으면 시작 페이지와 달라야 함", startPage, farPage)

            repeat(steps) {
                val before = viewModel.uiState.value.currentPage
                viewModel.previous()
                waitUntilTrue { viewModel.uiState.value.currentPage != before }
            }

            assertEquals(
                "정방향으로 넘긴 만큼 역방향으로 돌아오면 시작 페이지와 정확히 같아야 함(방문 이력 스택)",
                startPage,
                viewModel.uiState.value.currentPage,
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

        fun testParams() = PaginationParams(
            fontFamily = FontFamily.Default,
            fontSizeSp = 18f.sp,
            lineHeightMultiplier = 1.5f,
            letterSpacingSp = 0f.sp,
            contentWidthPx = 1000,
            contentHeightPx = 2000,
            textColor = Color.Black,
        )
    }
}

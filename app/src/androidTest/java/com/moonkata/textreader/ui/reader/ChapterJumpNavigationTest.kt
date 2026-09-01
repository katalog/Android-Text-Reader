package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.parser.ChapterJumpNavigator
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 챕터 점프 모드(N등분 내비게이션)의 핵심 계약: chapterJumpEnabled가 켜져 있으면 next()/previous()가
 * 일반 페이지 넘김이 아니라 ChapterJumpNavigator가 계산한 breakpoint를 그대로 따라가야 한다.
 * 화면 렌더링 없이 ReaderViewModel을 직접 구동한다 — 오프셋 이동은 페이지 모드/스크롤 모드 어느 쪽이든
 * updateCurrentOffset을 거치므로 pageTurnMode 설정과 무관하게 검증 가능하다.
 */
@RunWith(AndroidJUnit4::class)
class ChapterJumpNavigationTest {

    @Test
    fun next_stepsThroughEqualDivisionsOfEachChapter_andPreviousRetracesThem() {
        val bookAsset = "Static.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        val divisions = 4
        runBlocking {
            settingsRepository.updateChapterJumpEnabled(true)
            settingsRepository.updateChapterJumpDivisions(divisions)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
        }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.chapters.isNotEmpty() }
            waitUntilTrue {
                val settings = viewModel.uiState.value.settings
                settings.chapterJumpEnabled && settings.chapterJumpDivisions == divisions
            }

            val state = viewModel.uiState.value
            val expectedBreakpoints = ChapterJumpNavigator.breakpoints(state.chapters, state.fullText.length, divisions)
            assertTrue("픽스처에 챕터 점프 지점이 충분히 있어야 함", expectedBreakpoints.size >= 20)

            val stepsToTest = 15
            repeat(stepsToTest) { i ->
                val before = viewModel.uiState.value.currentOffset
                viewModel.next()
                waitUntilTrue { viewModel.uiState.value.currentOffset != before }
                assertEquals(
                    "다음으로 이동한 위치가 ChapterJumpNavigator의 breakpoint와 일치해야 함",
                    expectedBreakpoints[i],
                    viewModel.uiState.value.currentOffset,
                )
            }

            // 첫 breakpoint(index 0)까지만 되짚는다 — 그 이전(맨 처음, breakpoint가 아닌 지점)으로 가는
            // 마지막 한 걸음은 챕터 점프가 아니라 일반 페이지 넘김으로 폴백해(스크롤 모드에선 이벤트만
            // 발생하고 오프셋이 안 바뀜) 이 테스트가 검증하려는 대상이 아니다.
            repeat(stepsToTest - 1) {
                val before = viewModel.uiState.value.currentOffset
                viewModel.previous()
                waitUntilTrue { viewModel.uiState.value.currentOffset != before }
            }

            assertEquals(
                "역방향으로 (정방향 - 1)번 돌아오면 첫 breakpoint로 정확히 돌아와야 함",
                expectedBreakpoints[0],
                viewModel.uiState.value.currentOffset,
            )
        } finally {
            runBlocking {
                settingsRepository.updateChapterJumpEnabled(originalSettings.chapterJumpEnabled)
                settingsRepository.updateChapterJumpDivisions(originalSettings.chapterJumpDivisions)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

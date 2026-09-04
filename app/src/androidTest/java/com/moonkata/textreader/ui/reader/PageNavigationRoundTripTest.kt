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
 * Verifies the core contract of the page-mode redesign (no global page list — only the single
 * currentPage is computed on demand) using a real, large novel: advancing forward N times and then
 * backward N times should return to exactly the starting page, without recomputation, thanks to the
 * visit-history stack.
 *
 * Drives ReaderViewModel directly without screen rendering — since page navigation no longer depends
 * on the Compose Pager/events, this contract can be fully verified with the view model alone.
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

        // This test uses the same DataStore as the real app — if other settings left on the real
        // device (vertical scroll mode, auto timer advance turned on, etc.) leak in, next()/previous()
        // may not go through page mode, or a timer could silently call next() extra times in the
        // background, throwing off the visit-history stack relative to the count the test expects —
        // so only the settings this test needs are forced to fixed values, then restored to their
        // originals when done.
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            // If chapterJumpEnabled is on, next()/previous() take ChapterJumpNavigator's
            // evenly-divided TOC breakpoints instead of the visit-history stack, becoming a
            // completely different navigation mode than the "per-page visit history" contract this
            // test verifies.
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
            assertNotEquals("After advancing several pages, it should differ from the starting page", startPage, farPage)

            repeat(steps) {
                val before = viewModel.uiState.value.currentPage
                viewModel.previous()
                waitUntilTrue { viewModel.uiState.value.currentPage != before }
            }

            assertEquals(
                "Going backward the same number of steps advanced forward should return to exactly the starting page (visit-history stack)",
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

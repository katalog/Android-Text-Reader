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
 * Verifies, using a real large fixture (`Heuk.txt`, 5 chapters), that chapter detection (a
 * line-by-line regex scan over the whole text, which takes longer the larger the novel) does not
 * block the first page from displaying. If `loadBook()` regresses to the old approach of only turning
 * off `isLoading` after chapter detection also finishes, chapters would already be fully populated at
 * that moment, and this test would fail.
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

        // Pin/restore settings left in the production DataStore for the same reason as PageNavigationRoundTripTest.
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
        }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)

            // Poll tightly (every 2ms) for the moment isLoading turns off, and capture the chapters
            // state at that exact instant — the default 50ms interval used by waitUntilTrue elsewhere
            // could skip past this brief transition window and end up observing chapters that are
            // already populated, so a tighter interval is used here.
            var chaptersWhenLoadingFinished: List<Chapter>? = null
            waitUntilTrue(timeoutMs = 10_000, intervalMs = 2) {
                val state = viewModel.uiState.value
                if (!state.isLoading && chaptersWhenLoadingFinished == null) {
                    chaptersWhenLoadingFinished = state.chapters
                }
                !state.isLoading
            }

            assertTrue(
                "When loading finishes, only the paragraphs needed to show a page need to be ready — " +
                    "chapter detection (a slow full-text regex scan) doesn't need to have finished yet. " +
                    "If chapters are already populated at this point, chapter detection is blocking " +
                    "loading again (a regression)",
                chaptersWhenLoadingFinished?.isEmpty() ?: true,
            )
            assertTrue("Paragraphs needed to show a page should be ready once loading finishes", viewModel.uiState.value.paragraphs.isNotEmpty())

            // Without waiting for chapter detection, confirm that page navigation actually works as soon as loading finishes.
            viewModel.onViewportMeasured(TestTextMeasurer.create(application), PageNavigationRoundTripTest.testParams())
            waitUntilTrue { viewModel.uiState.value.currentPage != null }
            val startPage = viewModel.uiState.value.currentPage

            viewModel.nextPage()
            waitUntilTrue { viewModel.uiState.value.currentPage != startPage }
            assertNotEquals("nextPage() should actually advance the page right after the first page is computed", startPage, viewModel.uiState.value.currentPage)

            // Chapter detection keeps running in the background and should eventually populate normally (regression guard).
            waitUntilTrue(timeoutMs = 10_000) { viewModel.uiState.value.chapters.isNotEmpty() }
            assertEquals(
                "Chapters starting with \"## \" should be detected matching the original's chapter count (same expectation as ChapterDetectionRegressionTest)",
                5,
                viewModel.uiState.value.chapters.size,
            )
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    companion object {
        private const val BOOK_ASSET = "Heuk.txt"
    }
}

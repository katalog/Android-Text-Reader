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
 * The real path arriving via search result/TOC/chapter jump — verifies, using a point well into a
 * real novel's latter half (definitely far from the starting position), that jumpToOffset immediately
 * computes and shows the page starting at the target offset. The old architecture (a full-book page
 * list) had a bug where jumping to a far, not-yet-computed point would clamp to the nearest end of the
 * computed range — the current architecture computes exactly one page starting from that point on
 * every jump, so the bug itself can no longer structurally recur — it's kept anyway as a regression guard.
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

        // Since the real device's actual DataStore is shared, pageTurnMode is forced to a fixed value
        // so jumpToOffset takes the page-mode path (jumpToPageAt) — if it's left in vertical-scroll
        // mode, currentPage never updates at all and the test fails with a timeout. Restored to its
        // original value when done.
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
            check(targetOffset >= 0) { "Couldn't find the test marker (\"$marker\") in the book's latter half — the fixture may have changed" }

            viewModel.jumpToOffset(targetOffset)
            waitUntilTrue { viewModel.uiState.value.currentPage?.startOffset ?: -1 >= halfway }

            val page = viewModel.uiState.value.currentPage!!
            val pageText = fullText.substring(page.startOffset, page.endOffset)
            assertTrue(
                "The page jumped to should show the text at the target point (\"$marker\")",
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

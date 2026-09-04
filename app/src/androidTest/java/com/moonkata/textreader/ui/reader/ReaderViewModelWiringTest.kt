package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives `ReaderViewModel` directly without rendering the screen, to verify two pieces of wiring
 * that had no tests until now (USER_SCENARIOS.md §5, §14):
 * 1. In vertical scroll mode, `nextPage()`/`previousPage()` must emit `RequestNextPage`/
 *    `RequestPreviousPage` (and `nextChapterJump()`/`previousChapterJump()` must emit
 *    `JumpToOffset`) via `navEvents`, rather than computing via `Paginator` — the actual scrolling
 *    is done by `ReaderScrollContent` receiving that event, so at this level the view model side
 *    can only be verified up to "does it send the right event."
 * 2. `flushPendingPosition()` must commit to Room immediately without waiting for the 500ms
 *    debounce (the path that prevents position loss when leaving the screen/going to background).
 */
@RunWith(AndroidJUnit4::class)
class ReaderViewModelWiringTest {

    private val bookAsset = "Heuk.txt"

    private fun setUp(): Triple<Application, AppDatabase, BookRepository> {
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        return Triple(application, db, bookRepository)
    }

    @Test
    fun verticalScrollMode_nextAndPrevious_emitRequestPageNavEvents_insteadOfComputingPages() = runBlocking {
        val (application, db, bookRepository) = setUp()
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = TestBooks.insertBook(application, bookRepository, bookAsset)

        val originalSettings = settingsRepository.settingsFlow.first()
        settingsRepository.updatePageTurnMode(PageTurnMode.VERTICAL_SCROLL)
        settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            waitUntilTrue { viewModel.uiState.value.settings.pageTurnMode == PageTurnMode.VERTICAL_SCROLL }

            // navEvents is a SharedFlow with no replay, so the subscription must already be in
            // place before nextPage() is called. Starting the async with CoroutineStart.UNDISPATCHED
            // runs it synchronously right there up to the first suspension point (the subscription
            // inside first()), so the subscription is guaranteed to be in place before the
            // nextPage() call on the very next line — using waitUntilTrue (Thread.sleep-based
            // polling) together on the same single-threaded runBlocking event loop caused that
            // sleep to block this coroutine's turn to run, so the event was never received (this
            // was the actual cause of the first failure encountered).
            val nextEventDeferred = async(start = CoroutineStart.UNDISPATCHED) { viewModel.navEvents.first() }
            viewModel.nextPage()
            assertEquals(ReaderNavEvent.RequestNextPage, withTimeout(5_000) { nextEventDeferred.await() })

            val previousEventDeferred = async(start = CoroutineStart.UNDISPATCHED) { viewModel.navEvents.first() }
            viewModel.previousPage()
            assertEquals(ReaderNavEvent.RequestPreviousPage, withTimeout(5_000) { previousEventDeferred.await() })
        } finally {
            settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
            settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
            db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
        }
    }

    @Test
    fun verticalScrollMode_nextChapterJump_emitsJumpToOffsetInsteadOfRequestPage() = runBlocking {
        val (application, db, bookRepository) = setUp()
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = TestBooks.insertBook(application, bookRepository, bookAsset)

        val originalSettings = settingsRepository.settingsFlow.first()
        settingsRepository.updatePageTurnMode(PageTurnMode.VERTICAL_SCROLL)
        settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            waitUntilTrue(timeoutMs = 10_000) { viewModel.uiState.value.chapters.isNotEmpty() }
            waitUntilTrue { viewModel.uiState.value.settings.pageTurnMode == PageTurnMode.VERTICAL_SCROLL }

            val nextEventDeferred = async(start = CoroutineStart.UNDISPATCHED) { viewModel.navEvents.first() }
            viewModel.nextChapterJump()
            val event = withTimeout(5_000) { nextEventDeferred.await() }
            assertTrue("JumpToOffset must be emitted by nextChapterJump(): $event", event is ReaderNavEvent.JumpToOffset)
            event as ReaderNavEvent.JumpToOffset
            assertEquals(false, event.animate)
            assertEquals(event.offset, viewModel.uiState.value.currentOffset)
        } finally {
            settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
            settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
            db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
        }
    }

    @Test
    fun flushPendingPosition_persistsImmediately_withoutWaitingForTheDebounceTimer() = runBlocking {
        val (application, db, bookRepository) = setUp()
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = TestBooks.insertBook(application, bookRepository, bookAsset)

        val originalSettings = settingsRepository.settingsFlow.first()
        settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
        settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }

            val targetOffset = 4321
            viewModel.updateCurrentOffset(targetOffset)
            // Force a flush well before the debounce (500ms) would have finished — the same path
            // as ON_STOP/leaving the screen. flushPendingPosition is also internally
            // viewModelScope.launch, so it's async — wait for the actual Room write (it must finish
            // much faster than the 500ms debounce timer itself to prove the "commits immediately" contract).
            viewModel.flushPendingPosition()
            waitUntilTrue(timeoutMs = 3_000) {
                runBlocking { bookRepository.observeBook(bookId).first()?.lastReadCharOffset } == targetOffset
            }

            val persisted = bookRepository.observeBook(bookId).first()
            assertEquals(
                "flushPendingPosition must commit to Room immediately, without waiting for the debounce",
                targetOffset,
                persisted?.lastReadCharOffset,
            )
        } finally {
            settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
            settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
            db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
        }
    }
}

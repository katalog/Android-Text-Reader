package com.moonkata.textreader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.R
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.parser.ChapterJumpNavigator
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.TestTextMeasurer
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
import java.io.File

/**
 * The core contract of chapter jump (N-way division navigation): nextChapterJump()/
 * previousChapterJump() must follow the breakpoints computed by ChapterJumpNavigator exactly,
 * rather than doing a normal page turn. Drives ReaderViewModel directly without rendering the
 * screen — offset movement always goes through updateCurrentOffset regardless of page mode vs.
 * scroll mode, so this is verifiable independent of the pageTurnMode setting.
 */
@RunWith(AndroidJUnit4::class)
class ChapterJumpNavigationTest {

    @Test
    fun next_stepsThroughEqualDivisionsOfEachChapter_andPreviousRetracesThem() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        val divisions = 4
        runBlocking {
            settingsRepository.updateChapterJumpDivisions(divisions)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
        }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.chapters.isNotEmpty() }
            waitUntilTrue { viewModel.uiState.value.settings.chapterJumpDivisions == divisions }

            val state = viewModel.uiState.value
            val expectedBreakpoints = ChapterJumpNavigator.breakpoints(state.chapters, state.fullText.length, divisions, state.fullText)
            assertTrue("The fixture must have enough chapter jump breakpoints", expectedBreakpoints.size >= 20)

            val stepsToTest = 15
            repeat(stepsToTest) { i ->
                val before = viewModel.uiState.value.currentOffset
                viewModel.nextChapterJump()
                waitUntilTrue { viewModel.uiState.value.currentOffset != before }
                assertEquals(
                    "The position moved to by nextChapterJump() must match ChapterJumpNavigator's breakpoint",
                    expectedBreakpoints[i],
                    viewModel.uiState.value.currentOffset,
                )
            }

            // Only retrace back to the first breakpoint (index 0) — the last step back before that
            // (to the very beginning, a non-breakpoint position) falls back to a normal page turn
            // instead of a chapter jump (in scroll mode only an event fires and the offset doesn't
            // change), which is outside what this test is meant to verify.
            repeat(stepsToTest - 1) {
                val before = viewModel.uiState.value.currentOffset
                viewModel.previousChapterJump()
                waitUntilTrue { viewModel.uiState.value.currentOffset != before }
            }

            assertEquals(
                "Going back (forward count - 1) times must land exactly back on the first breakpoint",
                expectedBreakpoints[0],
                viewModel.uiState.value.currentOffset,
            )
        } finally {
            runBlocking {
                settingsRepository.updateChapterJumpDivisions(originalSettings.chapterJumpDivisions)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun chapterJumpOnBookWithNoDetectedChapters_emitsNoPatternMessage_andStillTurnsThePage() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)

        // Plain body text with no "##"-prefixed line anywhere, so the default chapter pattern
        // matches nothing and `chapters` stays empty once detection finishes.
        val testFile = File.createTempFile("no_chapters_test", ".txt", application.cacheDir).apply {
            writeText((1..50).joinToString("\n\n") { "Plain paragraph number $it, nothing chapter-like here." })
        }
        val bookId = bookRepository.findOrCreateBook(BookSource.PlainTxt(Uri.fromFile(testFile)), testFile.name, testFile.length())

        val originalSettings = settingsRepository.settingsFlow.first()
        settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
        settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            // Detection is a regex scan over a short string on a background dispatcher — this is far
            // more time than it needs to finish, so `chapters` reflects "found none" rather than
            // "hasn't run yet" (a real, much larger book could still race this on a slow device;
            // ReaderViewModel doesn't currently distinguish the two states).
            Thread.sleep(500)
            assertTrue("Fixture must genuinely have zero detected chapters for this test to mean anything", viewModel.uiState.value.chapters.isEmpty())

            // HORIZONTAL_PAGE + a real viewport measurement so the "still turns the page" fallback
            // updates currentOffset synchronously via advancePageForward() — no Compose UI needed,
            // unlike scroll mode, where a page turn only actually moves the offset once
            // ReaderScrollContent's LazyColumn is around to consume the RequestNextPage nav event.
            viewModel.onViewportMeasured(TestTextMeasurer.create(application), PageNavigationRoundTripTest.testParams())
            waitUntilTrue { viewModel.uiState.value.currentPage != null }

            val expectedMessage = application.getString(R.string.reader_chapter_jump_no_pattern)

            val pageBefore = viewModel.uiState.value.currentOffset
            val messageDeferred = async(start = CoroutineStart.UNDISPATCHED) { viewModel.messages.first() }
            viewModel.nextChapterJump()
            val messageRes = withTimeout(5_000) { messageDeferred.await() }
            assertEquals(expectedMessage, application.getString(messageRes))
            waitUntilTrue { viewModel.uiState.value.currentOffset != pageBefore }
        } finally {
            testFile.delete()
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

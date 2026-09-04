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
 * The core contract of chapter jump mode (N-way division navigation): when chapterJumpEnabled is
 * on, next()/previous() must follow the breakpoints computed by ChapterJumpNavigator exactly,
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
            val expectedBreakpoints = ChapterJumpNavigator.breakpoints(state.chapters, state.fullText.length, divisions, state.fullText)
            assertTrue("The fixture must have enough chapter jump breakpoints", expectedBreakpoints.size >= 20)

            val stepsToTest = 15
            repeat(stepsToTest) { i ->
                val before = viewModel.uiState.value.currentOffset
                viewModel.next()
                waitUntilTrue { viewModel.uiState.value.currentOffset != before }
                assertEquals(
                    "The position moved to by next() must match ChapterJumpNavigator's breakpoint",
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
                viewModel.previous()
                waitUntilTrue { viewModel.uiState.value.currentOffset != before }
            }

            assertEquals(
                "Going back (forward count - 1) times must land exactly back on the first breakpoint",
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

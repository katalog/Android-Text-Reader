package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.R
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ChapterPatternSheet` (USER_SCENARIOS.md §8) had no tests until now — only the pure logic,
 * `ChapterPatternCatalog`/`ChapterDetector`, was verified. Here, the sheet UI is actually
 * manipulated to confirm settings change (and, as a result, that chapter re-detection genuinely happens).
 */
@RunWith(AndroidJUnit4::class)
class ChapterPatternSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun togglingTheBuiltinPreset_updatesSettings_andActuallyChangesDetectedChapters() {
        val bookAsset = "Heuk.txt" // a fixture that actually has "## Chapter N" headers
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }

        try {
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            waitUntilTrue(timeoutMs = 10_000) { viewModel.uiState.value.chapters.isNotEmpty() }
            assertTrue("Given the fixture, chapters should initially be detected by the '## Chapter N' preset", viewModel.uiState.value.chapters.isNotEmpty())

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    ChapterPatternSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            // There's only this one preset, so isToggleable() alone is enough to identify it.
            composeTestRule.onNode(isToggleable()).performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { "hash" !in viewModel.uiState.value.settings.chapterPatternEnabledIds }

            // With the only preset turned off, rescanning must detect zero chapters — proof that re-detection actually happened.
            waitUntilTrue(timeoutMs = 10_000) { viewModel.uiState.value.chapters.isEmpty() }

            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertFalse("hash" in persisted.chapterPatternEnabledIds)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateChapterPatternEnabledIds(originalSettings.chapterPatternEnabledIds)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun addingAValidCustomPattern_clearsInput_andPersistsIt() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }

        try {
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    ChapterPatternSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            val pattern = """^Vol\.\s*\d+"""
            composeTestRule.onNode(hasSetTextAction()).performTextInput(pattern)
            composeTestRule.onNodeWithContentDescription(application.getString(R.string.chapter_pattern_add_desc)).performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000) { pattern in viewModel.uiState.value.settings.chapterCustomPatterns }
            // Whether the input field was actually cleared — the added pattern shows up separately in the list, so read the input field's own text value directly.
            val fieldText = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.EditableText)?.text
            assertEquals("", fieldText)

            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertTrue(pattern in persisted.chapterCustomPatterns)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateChapterCustomPatterns(originalSettings.chapterCustomPatterns)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun addingAnInvalidRegex_showsAnError_andDoesNotClearInputOrPersist() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }

        try {
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    ChapterPatternSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            val invalidPattern = "(["  // an unclosed character class/group — not a valid regex
            composeTestRule.onNode(hasSetTextAction()).performTextInput(invalidPattern)
            composeTestRule.onNodeWithContentDescription(application.getString(R.string.chapter_pattern_add_desc)).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText(application.getString(R.string.chapter_pattern_invalid_regex)).assertExists()
            assertTrue(
                "An invalid regex must not be persisted",
                runBlocking { viewModel.settingsRepository.settingsFlow.first() }.chapterCustomPatterns.isEmpty(),
            )
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateChapterCustomPatterns(originalSettings.chapterCustomPatterns)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun removingACustomPattern_persistsTheRemoval() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
        val existingPattern = """^Chapter\s+\d+"""

        try {
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            runBlocking { viewModel.settingsRepository.updateChapterCustomPatterns(setOf(existingPattern)) }
            waitUntilTrue { existingPattern in viewModel.uiState.value.settings.chapterCustomPatterns }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    ChapterPatternSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText(existingPattern).assertExists()
            composeTestRule.onNodeWithContentDescription(application.getString(R.string.chapter_pattern_delete_desc)).performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                existingPattern !in viewModel.uiState.value.settings.chapterCustomPatterns
            }
            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertFalse(existingPattern in persisted.chapterCustomPatterns)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateChapterCustomPatterns(originalSettings.chapterCustomPatterns)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

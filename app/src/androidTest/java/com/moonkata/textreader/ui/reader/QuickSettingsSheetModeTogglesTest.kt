package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.LineBreakMode
import com.moonkata.textreader.data.datastore.OrientationLock
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `QuickSettingsSheetTest` only covers font/margins/theme/transition animation, but the sheet also
 * has three more toggles documented as real user scenarios (USER_SCENARIOS.md §11) — reading mode
 * switching, line-break reflow mode, and keep-screen-on. These paths had zero automated tests
 * until now, so they're targeted separately here.
 */
@RunWith(AndroidJUnit4::class)
class QuickSettingsSheetModeTogglesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pageTurnMode_lineBreakMode_andKeepScreenOn_toggleAndPersist() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }

        val targetPageTurnMode = if (originalSettings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE) PageTurnMode.VERTICAL_SCROLL else PageTurnMode.HORIZONTAL_PAGE
        val pageTurnLabel = if (targetPageTurnMode == PageTurnMode.HORIZONTAL_PAGE) "Page turn" else "Scroll"
        val targetLineBreakMode = if (originalSettings.lineBreakMode == LineBreakMode.PRESERVE) LineBreakMode.REFLOW else LineBreakMode.PRESERVE
        val lineBreakLabel = if (targetLineBreakMode == LineBreakMode.PRESERVE) "Keep original" else "Reflow into paragraphs"
        val targetKeepScreenOn = !originalSettings.keepScreenOnEnabled
        val targetOrientationLock = when (originalSettings.orientationLock) {
            OrientationLock.AUTO -> OrientationLock.PORTRAIT
            OrientationLock.PORTRAIT -> OrientationLock.LANDSCAPE
            OrientationLock.LANDSCAPE -> OrientationLock.AUTO
        }
        val orientationLabel = mapOf(
            OrientationLock.AUTO to "Auto",
            OrientationLock.PORTRAIT to "Portrait",
            OrientationLock.LANDSCAPE to "Landscape",
        ).getValue(targetOrientationLock)

        try {
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    QuickSettingsSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText(pageTurnLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.pageTurnMode == targetPageTurnMode }

            composeTestRule.onNodeWithText(lineBreakLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.lineBreakMode == targetLineBreakMode }

            composeTestRule.onNodeWithText("Keep screen on").performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.keepScreenOnEnabled == targetKeepScreenOn }

            composeTestRule.onNodeWithText(orientationLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.orientationLock == targetOrientationLock }

            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertEquals("Reading mode switch must be persisted to DataStore", targetPageTurnMode, persisted.pageTurnMode)
            assertEquals("Line-break reflow mode change must be persisted to DataStore", targetLineBreakMode, persisted.lineBreakMode)
            assertEquals("Keep-screen-on toggle must be persisted to DataStore", targetKeepScreenOn, persisted.keepScreenOnEnabled)
            assertEquals("Orientation lock must be persisted to DataStore", targetOrientationLock, persisted.orientationLock)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                viewModel.settingsRepository.updateLineBreakMode(originalSettings.lineBreakMode)
                viewModel.settingsRepository.updateKeepScreenOnEnabled(originalSettings.keepScreenOnEnabled)
                viewModel.settingsRepository.updateOrientationLock(originalSettings.orientationLock)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

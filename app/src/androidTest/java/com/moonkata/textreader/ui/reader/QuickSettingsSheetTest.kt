package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.PageTransitionAnimation
import com.moonkata.textreader.data.datastore.ThemePreset
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
 * Verifies that changing a value in the settings sheet is reflected in the screen (uiState) and
 * actually persisted to DataStore too. Since ReaderViewModel uses the production DataStore directly,
 * the original values are remembered before starting and restored afterward so the test doesn't
 * permanently change the real device's settings.
 */
@RunWith(AndroidJUnit4::class)
class QuickSettingsSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun changingFontMarginThemeAndTransition_updatesUiAndPersistsToDataStore() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }

        val targetFontSize = if (originalSettings.fontSizeSp < 30f) originalSettings.fontSizeSp + 1f else originalSettings.fontSizeSp - 1f
        val targetMargin = if (originalSettings.marginHorizontalDp < 70f) originalSettings.marginHorizontalDp + 4f else originalSettings.marginHorizontalDp - 4f
        val targetTheme = when (originalSettings.themePreset) {
            ThemePreset.LIGHT -> ThemePreset.DARK
            ThemePreset.DARK -> ThemePreset.SEPIA
            else -> ThemePreset.LIGHT
        }
        val targetTransition = when (originalSettings.pageTransitionAnimation) {
            PageTransitionAnimation.NONE -> PageTransitionAnimation.SLIDE
            PageTransitionAnimation.SLIDE -> PageTransitionAnimation.COVER
            PageTransitionAnimation.COVER -> PageTransitionAnimation.NONE
        }
        val themeLabel = mapOf(
            ThemePreset.LIGHT to "Light",
            ThemePreset.DARK to "Dark",
            ThemePreset.SEPIA to "Sepia",
        ).getValue(targetTheme)
        val transitionLabel = mapOf(
            PageTransitionAnimation.NONE to "None",
            PageTransitionAnimation.SLIDE to "Slide",
            PageTransitionAnimation.COVER to "Cover",
        ).getValue(targetTransition)

        try {
            waitUntilTrue { viewModel.uiState.value.settings.fontSizeSp == originalSettings.fontSizeSp }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    QuickSettingsSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            val fontSizeButtonDescription = if (targetFontSize > originalSettings.fontSizeSp) "Increase Size" else "Decrease Size"
            composeTestRule.onNodeWithContentDescription(fontSizeButtonDescription).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.fontSizeSp == targetFontSize }

            val marginButtonDescription = if (targetMargin > originalSettings.marginHorizontalDp) "Increase Left/right" else "Decrease Left/right"
            composeTestRule.onNodeWithContentDescription(marginButtonDescription).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.marginHorizontalDp == targetMargin }

            composeTestRule.onNodeWithText(themeLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.themePreset == targetTheme }

            composeTestRule.onNodeWithText(transitionLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.pageTransitionAnimation == targetTransition }

            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertEquals("Font size change should be persisted to DataStore", targetFontSize, persisted.fontSizeSp)
            assertEquals("Margin change should be persisted to DataStore", targetMargin, persisted.marginHorizontalDp)
            assertEquals("Theme change should be persisted to DataStore", targetTheme, persisted.themePreset)
            assertEquals("Transition animation change should be persisted to DataStore", targetTransition, persisted.pageTransitionAnimation)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateFontSizeSp(originalSettings.fontSizeSp)
                viewModel.settingsRepository.updateMarginHorizontalDp(originalSettings.marginHorizontalDp)
                viewModel.settingsRepository.updateThemePreset(originalSettings.themePreset)
                viewModel.settingsRepository.updatePageTransitionAnimation(originalSettings.pageTransitionAnimation)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

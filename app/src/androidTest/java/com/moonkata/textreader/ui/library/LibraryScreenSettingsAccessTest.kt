package com.moonkata.textreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.model.FolderEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Added based on real-world feedback that settings (font/margins/theme/VSCode sync, etc.) should
 * be changeable from the library screen even with no book open — previously QuickSettingsSheet
 * was tied to ReaderViewModel, so there was no way to bring up the settings screen at all without
 * first opening a book (extracted the SettingsController interface, which LibraryViewModel now
 * also implements). Verifies that tapping the "Settings" icon in the top-right of the top bar
 * brings up the sheet, and that a value changed there is actually persisted to DataStore — this is
 * a new path that saves via LibraryViewModel rather than via ReaderViewModel, so it needs separate
 * verification.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenSettingsAccessTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val settingsRepository = ReaderSettingsRepository(application)

    @After
    fun cleanup() = runBlocking {
        settingsRepository.updateLastUsedSafTreeUri(null)
    }

    @Test
    fun settingsIconInLibrary_opensQuickSettingsSheet_andPersistsChangeToDataStore() {
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())

        val fakeRoot = Uri.parse("content://fake/settings-access-root")
        val folderBrowser = FakeFolderBrowser(
            mapOf(
                fakeRoot to listOf(
                    FolderEntry.TextFile(
                        name = "dummy.txt",
                        source = BookSource.PlainTxt(Uri.parse("content://fake/settings-access-root/dummy.txt")),
                        sizeBytes = 0,
                        lastModified = 0,
                    ),
                ),
            ),
        )

        val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, folderBrowser)
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        val targetFontSize = if (originalSettings.fontSizeSp < 30f) originalSettings.fontSizeSp + 1f else originalSettings.fontSizeSp - 1f

        try {
            composeTestRule.setContent {
                MaterialTheme {
                    LibraryScreen(onOpenBook = {}, viewModel = viewModel)
                }
            }

            composeTestRule.runOnUiThread { viewModel.onRootFolderSelected(fakeRoot) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.entries.isNotEmpty() }
            composeTestRule.waitForIdle()

            // Still no book open — the settings icon is there and pressing it brings up the sheet.
            composeTestRule.onNodeWithContentDescription("Settings").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Font").assertExists()

            val fontSizeButtonDescription = if (targetFontSize > originalSettings.fontSizeSp) "Increase Size" else "Decrease Size"
            composeTestRule.onNodeWithContentDescription(fontSizeButtonDescription).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.fontSizeSp == targetFontSize }

            val persisted = runBlocking { settingsRepository.settingsFlow.first() }
            assertEquals(
                "Settings changed from the library screen must also be persisted to DataStore via LibraryViewModel",
                targetFontSize,
                persisted.fontSizeSp,
            )
        } finally {
            runBlocking {
                settingsRepository.updateFontSizeSp(originalSettings.fontSizeSp)
            }
            testDb.close()
        }
    }
}

package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.font.FontCatalog
import com.moonkata.textreader.data.font.FontDownloadManager
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
 * Verifies the real click flow of `FontPickerSheet`: a font that hasn't been downloaded can't be
 * selected (its radio button is disabled), and tapping an already-downloaded font actually calls
 * `viewModel.selectFont` and is reflected in settings. The download mechanism itself
 * (success/failure/progress) is already covered by `FontDownloadManagerTest`/
 * `RealFontDownloadIntegrationTest`, so here an "already downloaded" state is faked with a dummy file
 * to quickly verify just the click flow (same pattern as `FontResolverTest` — the file contents don't
 * need to be a valid font).
 */
@RunWith(AndroidJUnit4::class)
class FontPickerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingADownloadedFont_selectsItInTheViewModel() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val fontDownloadManager = FontDownloadManager(application)
        val fontEntry = FontCatalog.entries.first()

        val originalFontFamilyId = runBlocking { settingsRepository.settingsFlow.first() }.fontFamilyId
        fontDownloadManager.delete(fontEntry)
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }

            // Fakes only the "already downloaded" state, without an actual download.
            fontDownloadManager.localFile(fontEntry).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    FontPickerSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("${fontEntry.displayName} (${fontEntry.license})").performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                viewModel.uiState.value.settings.fontFamilyId == fontEntry.id
            }

            assertEquals(fontEntry.id, viewModel.uiState.value.settings.fontFamilyId)
        } finally {
            runBlocking {
                settingsRepository.updateFontFamilyId(originalFontFamilyId)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
            fontDownloadManager.delete(fontEntry)
        }
    }
}

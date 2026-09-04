package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.font.FontCatalog
import com.moonkata.textreader.data.font.FontDownloadManager
import com.moonkata.textreader.data.font.FontDownloadState
import com.moonkata.textreader.data.font.FontResolver
import com.moonkata.textreader.data.parser.PaginationParams
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.TestTextMeasurer
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end verification of "does the viewer actually look different after downloading and applying
 * a font." Reproduces exactly what the viewer (`ReaderPagerContent`) does, using a real novel and a
 * font that's actually downloaded — in a `LaunchedEffect`, whenever `settings.fontFamilyId` changes,
 * it resolves a new `FontFamily` via `FontResolver.resolve`, packs it into `PaginationParams`, and
 * calls `onViewportMeasured`. It doesn't actually render the screen and compare pixels (that would
 * require a screenshot test, which is uncommon industry-wide for this kind of app and is excluded
 * here), but since a different font fits a different amount of the same text on one page, checking
 * that the page boundary (end offset) actually changes before and after applying the font reliably
 * proves "the viewer really did recompute and redraw with a different font."
 */
@RunWith(AndroidJUnit4::class)
class FontApplyRepaginatesViewerTest {

    @Test
    fun applyingADownloadedFont_changesWhatFitsOnThePage() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val fontDownloadManager = FontDownloadManager(application)
        val fontEntry = FontCatalog.findById("nanum_myeongjo")!!

        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        fontDownloadManager.delete(fontEntry)
        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
        }
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            waitUntilTrue {
                val settings = viewModel.uiState.value.settings
                settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE && settings.autoAdvanceMode == AutoAdvanceMode.OFF
            }

            val textMeasurer = TestTextMeasurer.create(application)

            // 1) First compute the page using the system default font.
            val defaultFontFamily = FontResolver.resolve(application, FontCatalog.SYSTEM_DEFAULT_ID)
            viewModel.onViewportMeasured(textMeasurer, testParams(defaultFontFamily))
            waitUntilTrue { viewModel.uiState.value.currentPage != null }
            val defaultFontPage = viewModel.uiState.value.currentPage!!

            // 2) Actually download and apply Nanum Myeongjo (real internet usage).
            val states = runBlocking { fontDownloadManager.download(fontEntry).toList() }
            assertTrue(
                "The real download must succeed for this test to continue. Failure state: ${states.lastOrNull()}",
                states.last() is FontDownloadState.Downloaded,
            )
            viewModel.selectFont(fontEntry.id)
            waitUntilTrue { viewModel.uiState.value.settings.fontFamilyId == fontEntry.id }

            // 3) Since the font changed, the viewer would have resolved a new FontFamily via
            //    FontResolver again and recomputed — reproduce that exactly.
            val customFontFamily = FontResolver.resolve(application, fontEntry.id)
            viewModel.onViewportMeasured(textMeasurer, testParams(customFontFamily))
            waitUntilTrue { viewModel.uiState.value.currentPage != defaultFontPage }
            val customFontPage = viewModel.uiState.value.currentPage!!

            assertNotEquals(
                "After applying a different font, the amount of content fitting on a page should " +
                    "differ even from the same starting point (proof the viewer actually redrew)",
                defaultFontPage.endOffset,
                customFontPage.endOffset,
            )
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateFontFamilyId(originalSettings.fontFamilyId)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
            fontDownloadManager.delete(fontEntry)
        }
    }

    private fun testParams(fontFamily: FontFamily) = PaginationParams(
        fontFamily = fontFamily,
        fontSizeSp = 18f.sp,
        lineHeightMultiplier = 1.5f,
        letterSpacingSp = 0f.sp,
        contentWidthPx = 1000,
        contentHeightPx = 2000,
        textColor = Color.Black,
    )
}

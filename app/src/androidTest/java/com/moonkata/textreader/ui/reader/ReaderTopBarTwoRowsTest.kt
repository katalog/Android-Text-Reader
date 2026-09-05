package com.moonkata.textreader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.R
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.repository.BookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the final result of the top-bar two-row restructuring (PR1-3) via real rendering:
 * 1. With chrome visible, all row 1 (back, settings) and row 2 (TOC, search) controls exist.
 * 2. The bottom bar (progress bar + percent text) has been fully removed, so while chrome is visible
 *    no text containing a "%" character should appear anywhere on screen.
 * 3. Hiding chrome (everything hidden) should still show the small percent indicator in the corner of
 *    the screen — that's separate UI, not part of the bottom bar, and was deliberately kept in PR3.
 */
@RunWith(AndroidJUnit4::class)
class ReaderTopBarTwoRowsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chromeVisible_showsAllRow1AndRow2Controls_andNoBottomBarPercentAnywhere() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)

        val firstParagraphMarker = "PAGE_MARKER_START_고유문단"
        val testFile = File(application.cacheDir, "reader_topbar_rows_test.txt").apply {
            val body = (1..300).joinToString("\n\n") { "그리고 이야기는 계속 이어졌다 문단 번호 $it 여기서 끝나지 않는다" }
            writeText("$firstParagraphMarker\n\n$body")
        }

        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
        }

        val bookId = runBlocking {
            bookRepository.findOrCreateBook(BookSource.PlainTxt(Uri.fromFile(testFile)), testFile.name, testFile.length())
        }

        try {
            composeTestRule.setContent {
                MaterialTheme {
                    ReaderScreen(bookId = bookId, onBack = {})
                }
            }

            // Once loading finishes, chrome automatically hides (see ReaderChromeAutoHideTest) — tap
            // the top 30% to bring it back up.
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                composeTestRule.onAllNodesWithText(firstParagraphMarker, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription(application.getString(R.string.reader_back_desc)).fetchSemanticsNodes().isEmpty()
            }
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.5f, height * 0.1f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription(application.getString(R.string.reader_back_desc)).fetchSemanticsNodes().isNotEmpty()
            }

            // Row 1: back + settings
            composeTestRule.onNodeWithContentDescription(application.getString(R.string.reader_back_desc)).assertExists()
            composeTestRule.onNodeWithContentDescription(application.getString(R.string.reader_settings_desc)).assertExists()

            // Row 2: TOC + search
            composeTestRule.onNodeWithContentDescription(application.getString(R.string.reader_toc_desc)).assertExists()
            composeTestRule.onNodeWithContentDescription(application.getString(R.string.reader_search_desc)).assertExists()

            // Since the bottom bar has been fully removed, no text containing "%" should exist
            // anywhere while chrome is visible right now (the corner percent indicator only appears
            // when chrome is hidden, so it has no effect here).
            assertTrue(
                "The bottom bar (progress bar + percent) was removed, so there should be no percent text while chrome is visible",
                composeTestRule.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isEmpty(),
            )

            // Tap the viewer area to hide chrome again — the corner percent indicator (separate UI)
            // should only appear now.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.75f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription(application.getString(R.string.reader_back_desc)).fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "The small percent indicator in the corner should remain while chrome is hidden (separate UI unrelated to the removed bottom bar)",
                composeTestRule.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isNotEmpty(),
            )
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

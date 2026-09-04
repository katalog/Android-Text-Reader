package com.moonkata.textreader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Verifies the actual intended behavior of the top/bottom bars (showChrome):
 * 1. When a file is opened, the bars are visible while loading, then disappear automatically
 *    once loading finishes, with no tap needed.
 * 2. Tapping the top 30% of the screen brings them back, and from there tapping the viewer area
 *    (below the top 30%) hides them again — that tap only closes the bars and must not turn
 *    the page.
 */
@RunWith(AndroidJUnit4::class)
class ReaderChromeAutoHideTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chromeHidesAfterLoad_reappearsOnTopTap_andHidesAgainOnViewerTapWithoutTurningPage() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)

        val firstParagraphMarker = "PAGE_MARKER_START_고유문단"
        val testFile = File(application.cacheDir, "reader_chrome_test.txt").apply {
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

            // 1. Once loading finishes (the first paragraph starts showing), the top/bottom bars
            //    must disappear on their own, with no tap.
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                composeTestRule.onAllNodesWithText(firstParagraphMarker, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty()
            }

            // 2. Tap the top 30% -> the bars must reappear.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.5f, height * 0.1f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
            }

            // 3. From there, tap the viewer area (below the top 30%) -> only the bars should
            //    disappear, and the page must not turn.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.75f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "The tap that closes the bars must not turn the page, so the first paragraph should still be on screen",
                composeTestRule.onAllNodesWithText(firstParagraphMarker, substring = true).fetchSemanticsNodes().isNotEmpty(),
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

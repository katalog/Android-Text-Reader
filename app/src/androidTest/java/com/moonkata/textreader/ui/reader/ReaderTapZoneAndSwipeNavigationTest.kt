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
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.datastore.SwipeTurnMode
import com.moonkata.textreader.data.datastore.TouchTurnMode
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.repository.BookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The mapping from `ReaderScreen`'s tap zones/swipes to `viewModel.next()`/`previous()`
 * (USER_SCENARIOS.md §4) has, until now, only been confirmed by `ReaderChromeAutoHideTest` in the
 * form of "a center tap doesn't turn the page" — whether the actual tap zones (left/right halves)
 * or swipes turn to next or previous according to `TouchTurnMode`/`SwipeTurnMode` has never been verified.
 */
@RunWith(AndroidJUnit4::class)
class ReaderTapZoneAndSwipeNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setUpBook(application: Application, bookRepository: BookRepository, firstMarker: String): Long {
        val testFile = File.createTempFile("tap_swipe_nav_test", ".txt", application.cacheDir).apply {
            val body = (1..300).joinToString("\n\n") { "그리고 이야기는 계속 이어졌다 문단 번호 $it 여기서 끝나지 않는다" }
            writeText("$firstMarker\n\n$body")
        }
        return runBlocking {
            bookRepository.findOrCreateBook(BookSource.PlainTxt(Uri.fromFile(testFile)), testFile.name, testFile.length())
        }
    }

    private fun waitForChromeToHide() {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun firstMarkerVisible(marker: String) =
        composeTestRule.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun tapZones_touchTurnModeStandard_rightGoesNext_leftGoesPrevious() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val marker = "TAP_STANDARD_MARKER"
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }

        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            settingsRepository.updateChapterJumpEnabled(false)
            settingsRepository.updateTouchTurnMode(TouchTurnMode.STANDARD)
        }
        val bookId = setUpBook(application, bookRepository, marker)

        try {
            composeTestRule.setContent {
                MaterialTheme { ReaderScreen(bookId = bookId, onBack = {}) }
            }
            composeTestRule.waitUntil(timeoutMillis = 10_000) { firstMarkerVisible(marker) }
            waitForChromeToHide()

            // Tap the right half (below the top 30%) -> goes to next page.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.8f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { !firstMarkerVisible(marker) }

            // In STANDARD mode, tapping the left half -> goes to previous page (the marker must reappear).
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.2f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { firstMarkerVisible(marker) }
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateChapterJumpEnabled(originalSettings.chapterJumpEnabled)
                settingsRepository.updateTouchTurnMode(originalSettings.touchTurnMode)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun tapZones_touchTurnModeBothNext_leftAlsoGoesNext() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val marker = "TAP_BOTHNEXT_MARKER"
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }

        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            settingsRepository.updateChapterJumpEnabled(false)
            settingsRepository.updateTouchTurnMode(TouchTurnMode.BOTH_NEXT)
        }
        val bookId = setUpBook(application, bookRepository, marker)

        try {
            composeTestRule.setContent {
                MaterialTheme { ReaderScreen(bookId = bookId, onBack = {}) }
            }
            composeTestRule.waitUntil(timeoutMillis = 10_000) { firstMarkerVisible(marker) }
            waitForChromeToHide()

            // Turn once via the right tap to move off the first page.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.8f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { !firstMarkerVisible(marker) }

            // In BOTH_NEXT mode, the left tap is also "next", so it must not return to the first page (the marker must stay hidden).
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.2f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty()
            }
            assertFalse(
                "In BOTH_NEXT mode, the left tap must also go to the next page, so it must not return to the first page",
                firstMarkerVisible(marker),
            )
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateChapterJumpEnabled(originalSettings.chapterJumpEnabled)
                settingsRepository.updateTouchTurnMode(originalSettings.touchTurnMode)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun swipeGestures_swipeTurnModeStandard_leftGoesNext_rightGoesPrevious() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val marker = "SWIPE_STANDARD_MARKER"
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }

        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            settingsRepository.updateChapterJumpEnabled(false)
            settingsRepository.updateSwipeTurnMode(SwipeTurnMode.STANDARD)
        }
        val bookId = setUpBook(application, bookRepository, marker)

        try {
            composeTestRule.setContent {
                MaterialTheme { ReaderScreen(bookId = bookId, onBack = {}) }
            }
            composeTestRule.waitUntil(timeoutMillis = 10_000) { firstMarkerVisible(marker) }
            waitForChromeToHide()

            // Swipe left (<-) -> always goes to the next page.
            composeTestRule.onRoot().performTouchInput { swipeLeft() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { !firstMarkerVisible(marker) }

            // In STANDARD mode, swiping right (->) -> goes to the previous page (marker returns).
            composeTestRule.onRoot().performTouchInput { swipeRight() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { firstMarkerVisible(marker) }
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateChapterJumpEnabled(originalSettings.chapterJumpEnabled)
                settingsRepository.updateSwipeTurnMode(originalSettings.swipeTurnMode)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun swipeGestures_swipeTurnModeBothNext_rightAlsoGoesNext() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val marker = "SWIPE_BOTHNEXT_MARKER"
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }

        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            settingsRepository.updateChapterJumpEnabled(false)
            settingsRepository.updateSwipeTurnMode(SwipeTurnMode.BOTH_NEXT)
        }
        val bookId = setUpBook(application, bookRepository, marker)

        try {
            composeTestRule.setContent {
                MaterialTheme { ReaderScreen(bookId = bookId, onBack = {}) }
            }
            composeTestRule.waitUntil(timeoutMillis = 10_000) { firstMarkerVisible(marker) }
            waitForChromeToHide()

            composeTestRule.onRoot().performTouchInput { swipeLeft() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { !firstMarkerVisible(marker) }

            // In BOTH_NEXT mode, swiping right is also "next", so it must not return to the first page.
            composeTestRule.onRoot().performTouchInput { swipeRight() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "In BOTH_NEXT mode, swiping right must also go to the next page, so it must not return to the first page",
                !firstMarkerVisible(marker),
            )
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateChapterJumpEnabled(originalSettings.chapterJumpEnabled)
                settingsRepository.updateSwipeTurnMode(originalSettings.swipeTurnMode)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

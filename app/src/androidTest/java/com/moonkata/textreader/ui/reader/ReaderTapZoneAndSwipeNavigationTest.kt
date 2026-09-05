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
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.R
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.PageGestureAction
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
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
 * The mapping from `ReaderScreen`'s tap zones/swipes to `viewModel.performGestureAction` (each of
 * the six gestures assigned an independent `PageGestureAction`) has, until now, only been confirmed
 * by `ReaderChromeAutoHideTest` in the form of "a center tap doesn't turn the page" — whether the
 * actual tap zones (left/right halves) or swipes turn to next or previous according to their
 * assigned action has never been verified.
 */
@RunWith(AndroidJUnit4::class)
class ReaderTapZoneAndSwipeNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()

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
            composeTestRule.onAllNodesWithContentDescription(application.getString(R.string.reader_back_desc)).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun firstMarkerVisible(marker: String) =
        composeTestRule.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun tapZones_leftPreviousRightNext_rightGoesNext_leftGoesPrevious() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val marker = "TAP_STANDARD_MARKER"
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }

        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            settingsRepository.updateTouchLeftAction(PageGestureAction.PREVIOUS_PAGE)
            settingsRepository.updateTouchRightAction(PageGestureAction.NEXT_PAGE)
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

            // touchLeftAction=PREVIOUS_PAGE -> tapping the left half goes to previous page (the marker must reappear).
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.2f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { firstMarkerVisible(marker) }
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateTouchLeftAction(originalSettings.touchLeftAction)
                settingsRepository.updateTouchRightAction(originalSettings.touchRightAction)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun tapZones_bothSidesNext_leftAlsoGoesNext() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val marker = "TAP_BOTHNEXT_MARKER"
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }

        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            settingsRepository.updateTouchLeftAction(PageGestureAction.NEXT_PAGE)
            settingsRepository.updateTouchRightAction(PageGestureAction.NEXT_PAGE)
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

            // Both zones are NEXT_PAGE, so the left tap must not return to the first page (the marker must stay hidden).
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.2f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription(application.getString(R.string.reader_back_desc)).fetchSemanticsNodes().isEmpty()
            }
            assertFalse(
                "When both tap zones are NEXT_PAGE, the left tap must also go to the next page, so it must not return to the first page",
                firstMarkerVisible(marker),
            )
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateTouchLeftAction(originalSettings.touchLeftAction)
                settingsRepository.updateTouchRightAction(originalSettings.touchRightAction)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun swipeGestures_leftNextRightPrevious_leftGoesNext_rightGoesPrevious() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val marker = "SWIPE_STANDARD_MARKER"
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }

        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            settingsRepository.updateSwipeLeftAction(PageGestureAction.NEXT_PAGE)
            settingsRepository.updateSwipeRightAction(PageGestureAction.PREVIOUS_PAGE)
        }
        val bookId = setUpBook(application, bookRepository, marker)

        try {
            composeTestRule.setContent {
                MaterialTheme { ReaderScreen(bookId = bookId, onBack = {}) }
            }
            composeTestRule.waitUntil(timeoutMillis = 10_000) { firstMarkerVisible(marker) }
            waitForChromeToHide()

            // Swipe left (<-) -> goes to the next page.
            composeTestRule.onRoot().performTouchInput { swipeLeft() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { !firstMarkerVisible(marker) }

            // swipeRightAction=PREVIOUS_PAGE -> swiping right (->) goes to the previous page (marker returns).
            composeTestRule.onRoot().performTouchInput { swipeRight() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { firstMarkerVisible(marker) }
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateSwipeLeftAction(originalSettings.swipeLeftAction)
                settingsRepository.updateSwipeRightAction(originalSettings.swipeRightAction)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun swipeGestures_bothDirectionsNext_rightAlsoGoesNext() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val marker = "SWIPE_BOTHNEXT_MARKER"
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }

        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            settingsRepository.updateSwipeLeftAction(PageGestureAction.NEXT_PAGE)
            settingsRepository.updateSwipeRightAction(PageGestureAction.NEXT_PAGE)
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

            // Both directions are NEXT_PAGE, so swiping right must also not return to the first page.
            composeTestRule.onRoot().performTouchInput { swipeRight() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription(application.getString(R.string.reader_back_desc)).fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "When both swipe directions are NEXT_PAGE, swiping right must also go to the next page, so it must not return to the first page",
                !firstMarkerVisible(marker),
            )
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateSwipeLeftAction(originalSettings.swipeLeftAction)
                settingsRepository.updateSwipeRightAction(originalSettings.swipeRightAction)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun verticalSwipes_inPagedMode_upGoesNext_downGoesPrevious() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val marker = "SWIPE_VERTICAL_PAGED_MARKER"
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }

        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            // Assigned to plain page actions rather than chapter jump — this test is only about
            // whether a vertical drag is detected and routed to its assigned action at all, which
            // ChapterJumpNavigationTest doesn't cover (it drives nextChapterJump()/
            // previousChapterJump() directly, never through the gesture-detection code).
            settingsRepository.updateSwipeUpAction(PageGestureAction.NEXT_PAGE)
            settingsRepository.updateSwipeDownAction(PageGestureAction.PREVIOUS_PAGE)
        }
        val bookId = setUpBook(application, bookRepository, marker)

        try {
            composeTestRule.setContent {
                MaterialTheme { ReaderScreen(bookId = bookId, onBack = {}) }
            }
            composeTestRule.waitUntil(timeoutMillis = 10_000) { firstMarkerVisible(marker) }
            waitForChromeToHide()

            composeTestRule.onRoot().performTouchInput { swipeUp() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { !firstMarkerVisible(marker) }

            composeTestRule.onRoot().performTouchInput { swipeDown() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { firstMarkerVisible(marker) }
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateSwipeUpAction(originalSettings.swipeUpAction)
                settingsRepository.updateSwipeDownAction(originalSettings.swipeDownAction)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    // A "vertical swipe still scrolls the content in VERTICAL_SCROLL mode" test was attempted here
    // but removed — see .docs/TESTING.md's "의도적으로 제외" for why (Compose UI test's synthetic
    // swipeUp() never reaches ReaderScrollContent's LazyColumn under this screen's outer pointerInput
    // Box, though a real touch on a real device scrolls it correctly).

    @Test
    fun tapZone_setToNoAction_doesNothing() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val marker = "TAP_NONE_MARKER"
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }

        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
            settingsRepository.updateTouchRightAction(PageGestureAction.NEXT_PAGE)
            settingsRepository.updateTouchLeftAction(PageGestureAction.NONE)
        }
        val bookId = setUpBook(application, bookRepository, marker)

        try {
            composeTestRule.setContent {
                MaterialTheme { ReaderScreen(bookId = bookId, onBack = {}) }
            }
            composeTestRule.waitUntil(timeoutMillis = 10_000) { firstMarkerVisible(marker) }
            waitForChromeToHide()

            // Move off the first page first via the right zone (assigned NEXT_PAGE), so a left tap
            // that (wrongly) still turned the page backward would be observable as the marker
            // reappearing.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.8f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { !firstMarkerVisible(marker) }

            // touchLeftAction=NONE -> tapping the left half must not turn the page at all.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.2f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription(application.getString(R.string.reader_back_desc)).fetchSemanticsNodes().isEmpty()
            }
            assertFalse(
                "A tap zone assigned NONE must not turn the page in either direction",
                firstMarkerVisible(marker),
            )
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateTouchRightAction(originalSettings.touchRightAction)
                settingsRepository.updateTouchLeftAction(originalSettings.touchLeftAction)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

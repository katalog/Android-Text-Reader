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
 * `ReaderScreen`의 탭 존/스와이프 → `viewModel.next()`/`previous()` 매핑(USER_SCENARIOS.md §4)은
 * 지금까지 `ReaderChromeAutoHideTest`가 "가운데 탭은 페이지를 안 넘긴다"만 확인했지, 실제 탭
 * 존(좌/우 절반)이나 스와이프가 `TouchTurnMode`/`SwipeTurnMode`에 따라 실제로 다음/이전 중 어느
 * 쪽으로 넘기는지는 검증된 적이 없다.
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
            composeTestRule.onAllNodesWithContentDescription("뒤로").fetchSemanticsNodes().isEmpty()
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

            // 오른쪽 절반(위쪽 30% 아래) 탭 -> 다음 페이지로.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.8f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { !firstMarkerVisible(marker) }

            // STANDARD 모드에서 왼쪽 절반 탭 -> 이전 페이지로(마커가 다시 보여야 함).
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

            // 오른쪽 탭으로 한 번 넘겨서 첫 페이지를 벗어남.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.8f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { !firstMarkerVisible(marker) }

            // BOTH_NEXT 모드에서는 왼쪽 탭도 "다음"이라 첫 페이지로 되돌아가면 안 된다(마커가 계속 안 보여야 함).
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.2f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("뒤로").fetchSemanticsNodes().isEmpty()
            }
            assertFalse(
                "BOTH_NEXT 모드에서는 왼쪽 탭도 다음 페이지로 가야 하므로 첫 페이지로 되돌아가면 안 됨",
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

            // 왼쪽으로 스와이프(<-) -> 항상 다음 페이지.
            composeTestRule.onRoot().performTouchInput { swipeLeft() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { !firstMarkerVisible(marker) }

            // STANDARD 모드에서 오른쪽으로 스와이프(->) -> 이전 페이지(마커 복귀).
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

            // BOTH_NEXT 모드에서는 오른쪽 스와이프도 "다음"이라 첫 페이지로 되돌아가면 안 된다.
            composeTestRule.onRoot().performTouchInput { swipeRight() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("뒤로").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "BOTH_NEXT 모드에서는 오른쪽 스와이프도 다음 페이지로 가야 하므로 첫 페이지로 되돌아가면 안 됨",
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

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
 * 상하단바(showChrome)의 실제 원하는 동작을 검증한다:
 * 1. 파일을 열면 로딩 중엔 상하단바가 보이다가, 로딩이 끝나면 탭 없이 자동으로 사라진다.
 * 2. 화면 위쪽 30%를 탭하면 다시 나타나고, 그 상태에서 뷰어 영역(위쪽 30% 아래)을 탭하면 다시
 *    사라진다 — 이 탭은 페이지를 넘기지 않고 오직 상하단바만 닫는다.
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
            settingsRepository.updateChapterJumpEnabled(false)
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

            // 1. 로딩이 끝나(첫 문단이 보이기 시작하면) 탭 없이도 상하단바가 저절로 사라져야 한다.
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                composeTestRule.onAllNodesWithText(firstParagraphMarker, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("뒤로").fetchSemanticsNodes().isEmpty()
            }

            // 2. 위쪽 30% 탭 → 상하단바가 다시 나타나야 한다.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.5f, height * 0.1f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("뒤로").fetchSemanticsNodes().isNotEmpty()
            }

            // 3. 그 상태에서 뷰어 영역(위쪽 30% 아래)을 탭 → 상하단바만 사라지고, 페이지는 안 넘어가야 한다.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.75f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("뒤로").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "상하단바를 닫는 탭은 페이지를 넘기면 안 되므로 첫 문단이 여전히 화면에 있어야 함",
                composeTestRule.onAllNodesWithText(firstParagraphMarker, substring = true).fetchSemanticsNodes().isNotEmpty(),
            )
        } finally {
            testFile.delete()
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateChapterJumpEnabled(originalSettings.chapterJumpEnabled)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

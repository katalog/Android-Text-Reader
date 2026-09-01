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
 * 상단바 2행 재구성(PR1~3)의 최종 결과를 실제 렌더링으로 검증한다:
 * 1. 크롬이 보이는 상태에서 1행(뒤로·설정)과 2행(목차·검색·챕터점프) 컨트롤이 전부 존재한다.
 * 2. 하단바(프로그레스바 + 퍼센트 텍스트)는 완전히 삭제됐으므로, 크롬이 보이는 동안에는 "%" 문자가
 *    들어간 텍스트가 화면 어디에도 없어야 한다.
 * 3. 크롬을 숨기면(전부 안 보이는 상태) 화면 구석의 작은 퍼센트 표시는 여전히 나타나야 한다 —
 *    이건 하단바 소속이 아니라 별개 UI라 PR3에서 의도적으로 유지한 부분.
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

            // 로딩이 끝나면 크롬이 자동으로 숨는다(ReaderChromeAutoHideTest 참고) — 위쪽 30%를 탭해서
            // 다시 띄운다.
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                composeTestRule.onAllNodesWithText(firstParagraphMarker, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("뒤로").fetchSemanticsNodes().isEmpty()
            }
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.5f, height * 0.1f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("뒤로").fetchSemanticsNodes().isNotEmpty()
            }

            // 1행: 뒤로가기 + 설정
            composeTestRule.onNodeWithContentDescription("뒤로").assertExists()
            composeTestRule.onNodeWithContentDescription("설정").assertExists()

            // 2행: 목차 + 검색 + 챕터점프 토글
            composeTestRule.onNodeWithContentDescription("목차").assertExists()
            composeTestRule.onNodeWithContentDescription("검색").assertExists()
            composeTestRule.onNodeWithText("챕터 점프", substring = true).assertExists()

            // 하단바가 완전히 삭제됐으므로, 크롬이 보이는 지금은 "%" 들어간 텍스트가 어디에도 없어야 한다
            // (구석 퍼센트 표시는 크롬이 숨겨졌을 때만 뜨므로 지금은 영향 없음).
            assertTrue(
                "하단바(프로그레스바 + 퍼센트)가 삭제됐으므로 크롬이 보이는 동안 퍼센트 텍스트가 없어야 함",
                composeTestRule.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isEmpty(),
            )

            // 뷰어 영역을 탭해 크롬을 다시 숨긴다 — 구석 퍼센트 표시(별개 UI)는 이때만 나타나야 한다.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.75f, height * 0.6f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithContentDescription("뒤로").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "크롬이 숨겨진 동안에는 구석의 작은 퍼센트 표시가 그대로 남아있어야 함(하단바 삭제와 무관한 별개 UI)",
                composeTestRule.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isNotEmpty(),
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

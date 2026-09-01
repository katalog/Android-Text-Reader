package com.moonkata.textreader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * ReaderScreen이 실제 책 내용을 불러와 보여주고, 뒤로가기 버튼이 콜백을 호출하는지 확인하는 기본
 * 렌더링 테스트. ReaderViewModel/ReaderViewModelFactory는 아직 LibraryViewModel처럼 DB를 주입받을
 * 수 없어(프로덕션 싱글톤 AppDatabase를 그대로 씀) 이 테스트도 실제 앱 DB를 쓰고, 끝나면 넣은 책
 * 기록을 지운다 — Phase 3에서 ReaderViewModel도 DI 가능하게 확장하면 인메모리 DB로 옮길 수 있다.
 */
@RunWith(AndroidJUnit4::class)
class ReaderScreenBasicTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loadsBookContent_andBackButtonInvokesCallback() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())

        val testFile = File(application.cacheDir, "reader_basic_test.txt").apply {
            writeText("첫 번째 문단입니다.\n\n두 번째 문단이고 여기 특별한 문장이 있습니다.")
        }
        val bookId = runBlocking {
            bookRepository.findOrCreateBook(BookSource.PlainTxt(Uri.fromFile(testFile)), testFile.name, testFile.length())
        }

        try {
            var backCount = 0
            composeTestRule.setContent {
                MaterialTheme {
                    ReaderScreen(bookId = bookId, onBack = { backCount++ })
                }
            }

            // 상하단바는 로딩이 끝나면 자동으로 숨겨지므로(ReaderChromeAutoHideTest 참고), 제목 텍스트가
            // 아니라 본문 내용으로 로딩 완료를 확인한다.
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithText("특별한 문장이 있습니다", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // 위쪽 30% 탭으로 상하단바를 다시 띄운 뒤 뒤로가기 버튼을 누른다.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.5f, height * 0.1f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithText(testFile.name).fetchSemanticsNodes().isNotEmpty()
            }

            composeTestRule.onNodeWithContentDescription("뒤로").performClick()
            assertTrue("뒤로가기 버튼을 누르면 onBack 콜백이 호출되어야 함", backCount > 0)
        } finally {
            testFile.delete()
            runBlocking {
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

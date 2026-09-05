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
import com.moonkata.textreader.R
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
 * Basic rendering test confirming ReaderScreen loads and displays actual book content, and that
 * the back button invokes the callback. ReaderViewModel/ReaderViewModelFactory can't yet be
 * injected with a DB like LibraryViewModel (it uses the production AppDatabase singleton as-is),
 * so this test also uses the real app DB and cleans up the book record it inserted when done —
 * this can move to an in-memory DB once Phase 3 extends ReaderViewModel to support DI too.
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

            // The top/bottom bars auto-hide once loading finishes (see ReaderChromeAutoHideTest), so
            // confirm loading is complete via body content rather than the title text.
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithText("특별한 문장이 있습니다", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Tap the top 30% to bring the top/bottom bars back, then press the back button.
            composeTestRule.onRoot().performTouchInput { click(Offset(width * 0.5f, height * 0.1f)) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithText(testFile.name).fetchSemanticsNodes().isNotEmpty()
            }

            composeTestRule.onNodeWithContentDescription(application.getString(R.string.reader_back_desc)).performClick()
            assertTrue("Pressing the back button should invoke the onBack callback", backCount > 0)
        } finally {
            testFile.delete()
            runBlocking {
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

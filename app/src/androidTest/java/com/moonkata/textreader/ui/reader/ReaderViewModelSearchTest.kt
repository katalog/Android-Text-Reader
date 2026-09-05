package com.moonkata.textreader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `search()` used to silently stop after 200 matches (regardless of how much of the book was left
 * unsearched) — a common word in a long book could hit that cap well before the end, so results
 * past wherever the 200th match happened to fall were missing with no indication anything was
 * cut off. Covers that a term appearing far more than 200 times now returns every occurrence.
 */
@RunWith(AndroidJUnit4::class)
class ReaderViewModelSearchTest {

    @Test
    fun search_withMoreThan200Matches_returnsAllOfThem() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())

        val matchCount = 500
        val testFile = File.createTempFile("search_no_cap_test", ".txt", application.cacheDir).apply {
            writeText((1..matchCount).joinToString("\n\n") { "고유단어 문단 번호 $it 여기서 끝나지 않는다" })
        }
        val bookId = runBlocking {
            bookRepository.findOrCreateBook(BookSource.PlainTxt(Uri.fromFile(testFile)), testFile.name, testFile.length())
        }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.fullText.isNotEmpty() }

            val results = viewModel.search("고유단어")

            assertTrue(
                "Expected all $matchCount matches, got only ${results.size} — the old 200-result cap must be gone",
                results.size == matchCount,
            )
        } finally {
            testFile.delete()
            runBlocking {
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

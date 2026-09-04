package com.moonkata.textreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the logic that checks whether the actual file can still be opened before offering it as a
 * "resume reading" candidate. Without that check, if the file has been deleted/moved and the user taps
 * "Continue", nothing catches the `FileNotFoundException` thrown by `BookContentReader.readBytes`, and
 * the app crashes (a bug that was actually reported this time).
 */
@RunWith(AndroidJUnit4::class)
class ResumeCandidateFileExistsTest {

    @Test
    fun bookRepository_bookFileExists_trueForRealFile_falseAfterItsDeleted() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())

        val testFile = File(application.cacheDir, "resume_exists_test.txt").apply { writeText("본문") }
        try {
            val bookId = runBlocking {
                bookRepository.findOrCreateBook(BookSource.PlainTxt(Uri.fromFile(testFile)), testFile.name, testFile.length())
            }
            val book = runBlocking { testDb.bookDao().getById(bookId).first() }!!

            assertTrue("A file that actually exists should be true", runBlocking { bookRepository.bookFileExists(book) })

            testFile.delete()

            assertFalse("Should be false after the file is deleted", runBlocking { bookRepository.bookFileExists(book) })
        } finally {
            testFile.delete()
            testDb.close()
        }
    }

    @Test
    fun libraryViewModel_offersResumeCandidate_whenFileStillExists() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)

        val testFile = File(application.cacheDir, "resume_vm_exists_test.txt").apply { writeText("본문") }
        try {
            val bookId = runBlocking {
                bookRepository.findOrCreateBook(BookSource.PlainTxt(Uri.fromFile(testFile)), testFile.name, testFile.length())
            }
            runBlocking { bookRepository.updateReadPosition(bookId, offset = 10, progressPercent = 0.5f) }

            val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, FakeFolderBrowser(emptyMap()))

            waitUntilTrue { viewModel.resumeCandidate.value != null }
            assertEquals(bookId, viewModel.resumeCandidate.value?.id)
        } finally {
            testFile.delete()
            testDb.close()
        }
    }

    @Test
    fun libraryViewModel_neverOffersResumeCandidate_whenItsFileIsGone() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)

        val testFile = File(application.cacheDir, "resume_vm_missing_test.txt").apply { writeText("본문") }
        try {
            val bookId = runBlocking {
                bookRepository.findOrCreateBook(BookSource.PlainTxt(Uri.fromFile(testFile)), testFile.name, testFile.length())
            }
            // Must be made to look "previously opened" to satisfy the resume-candidate condition (lastOpenedAt != null).
            runBlocking { bookRepository.updateReadPosition(bookId, offset = 10, progressPercent = 0.5f) }
            testFile.delete() // Now this book's actual file is gone.

            val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, FakeFolderBrowser(emptyMap()))

            // Existence checking a single local file is very fast — poll continuously for a
            // generously long window (1.5s), and fail immediately the moment a candidate shows up
            // even once during that window.
            repeat(30) {
                assertNull(
                    "A book with no file should never be offered as a resume candidate (if it were, tapping \"Continue\" would crash)",
                    viewModel.resumeCandidate.value,
                )
                Thread.sleep(50)
            }
        } finally {
            testFile.delete()
            testDb.close()
        }
    }
}

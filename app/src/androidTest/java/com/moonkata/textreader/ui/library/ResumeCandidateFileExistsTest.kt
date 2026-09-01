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
 * "이어서 읽기" 후보로 올리기 전에 실제 파일을 아직 열 수 있는지 확인하는 로직을 검증한다.
 * 확인 없이 후보로 올리면, 파일이 삭제/이동된 상태에서 "계속 보기"를 눌렀을 때
 * `BookContentReader.readBytes`가 던지는 `FileNotFoundException`을 잡는 곳이 없어 앱이 죽는다
 * (이번에 실제로 보고된 버그).
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

            assertTrue("실제로 존재하는 파일은 true여야 함", runBlocking { bookRepository.bookFileExists(book) })

            testFile.delete()

            assertFalse("파일을 지운 뒤에는 false여야 함", runBlocking { bookRepository.bookFileExists(book) })
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
            // "예전에 읽은 적 있음"으로 만들어야 이어서 읽기 후보 조건(lastOpenedAt != null)을 만족한다.
            runBlocking { bookRepository.updateReadPosition(bookId, offset = 10, progressPercent = 0.5f) }
            testFile.delete() // 이제 이 책의 실제 파일은 없다.

            val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, FakeFolderBrowser(emptyMap()))

            // 존재 확인은 로컬 파일 하나에 대한 확인이라 매우 빠르다 — 충분히 넉넉히(1.5초) 기다리는
            // 동안 계속 폴링하며, 그 사이 단 한 번이라도 후보가 뜨면 바로 실패시킨다.
            repeat(30) {
                assertNull(
                    "파일이 없는 책은 이어서 읽기 후보로 뜨면 안 됨(떴다면 \"계속 보기\"를 누를 때 크래시남)",
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

package com.moonkata.textreader.data.repository

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.file.BookSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `BookRepository`는 다른 여러 테스트에서 간접적으로만 exercise돼 왔다 — 여기서는 `findOrCreateBook`의
 * upsert 분기(신규/기존/relativePath 갱신)와, 실제 파일 I/O가 걸리는 `openBookContent`/`bookFileExists`를
 * 직접 겨냥한다.
 */
@RunWith(AndroidJUnit4::class)
class BookRepositoryTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDatabase
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        repository = BookRepository(application, db.bookDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tempTextFile(content: String): File {
        val file = File.createTempFile("book_repo_test", ".txt", application.cacheDir)
        file.writeText(content)
        return file
    }

    @Test
    fun findOrCreateBook_newSource_insertsANewRow() = runBlocking {
        val source = BookSource.PlainTxt(Uri.parse("file:///new.txt"))
        val id = repository.findOrCreateBook(source, "새 책", sizeBytes = 100)

        val book = repository.observeBook(id).first()
        assertEquals("새 책", book?.displayName)
        assertEquals(1, db.bookDao().getAllOrderByRecent().first().size)
    }

    @Test
    fun findOrCreateBook_sameSourceTwice_returnsTheSameId_withoutASecondRow() = runBlocking {
        val source = BookSource.PlainTxt(Uri.parse("file:///same.txt"))
        val firstId = repository.findOrCreateBook(source, "책", sizeBytes = 100)
        val secondId = repository.findOrCreateBook(source, "책", sizeBytes = 100)

        assertEquals(firstId, secondId)
        assertEquals(1, db.bookDao().getAllOrderByRecent().first().size)
    }

    @Test
    fun findOrCreateBook_existingBookWithADifferentRelativePath_updatesItInPlace() = runBlocking {
        val source = BookSource.PlainTxt(Uri.parse("file:///moved.txt"))
        val id = repository.findOrCreateBook(source, "책", sizeBytes = 100, relativePath = "")

        val idAfterMove = repository.findOrCreateBook(source, "책", sizeBytes = 100, relativePath = "folder/moved.txt")

        assertEquals("id는 그대로 유지돼야 함(새로 만들면 안 됨)", id, idAfterMove)
        assertEquals("folder/moved.txt", repository.observeBook(id).first()?.relativePath)
    }

    @Test
    fun markOpened_updatesTotalCharCountAndEncoding() = runBlocking {
        val id = repository.findOrCreateBook(BookSource.PlainTxt(Uri.parse("file:///opened.txt")), "책", sizeBytes = 100)
        repository.markOpened(id, totalCharCount = 42, encoding = "EUC-KR")

        val book = repository.observeBook(id).first()
        assertEquals(42, book?.totalCharCount)
        assertEquals("EUC-KR", book?.detectedEncoding)
    }

    @Test
    fun updateReadPosition_persistsOffsetAndProgress() = runBlocking {
        val id = repository.findOrCreateBook(BookSource.PlainTxt(Uri.parse("file:///pos.txt")), "책", sizeBytes = 100)
        repository.updateReadPosition(id, offset = 500, progressPercent = 0.25f)

        val book = repository.observeBook(id).first()
        assertEquals(500, book?.lastReadCharOffset)
        assertEquals(0.25f, book?.lastReadProgressPercent)
    }

    @Test
    fun deleteBook_removesTheRow() = runBlocking {
        val id = repository.findOrCreateBook(BookSource.PlainTxt(Uri.parse("file:///gone.txt")), "책", sizeBytes = 100)
        val book = repository.observeBook(id).first()!!

        repository.deleteBook(book)

        assertEquals(null, repository.observeBook(id).first())
    }

    @Test
    fun openBookContent_readsTheRealFileThroughTheFullPipeline() = runBlocking {
        val file = tempTextFile("실제 파일 내용입니다.")
        val book = repository.observeBook(
            repository.findOrCreateBook(BookSource.PlainTxt(Uri.fromFile(file)), file.name, sizeBytes = file.length()),
        ).first()!!

        val result = repository.openBookContent(book)

        assertEquals("실제 파일 내용입니다.", result.text)
    }

    @Test
    fun bookFileExists_trueForARealFile_falseAfterItsDeletedOnDisk() = runBlocking {
        val file = tempTextFile("사라질 파일")
        val book = repository.observeBook(
            repository.findOrCreateBook(BookSource.PlainTxt(Uri.fromFile(file)), file.name, sizeBytes = file.length()),
        ).first()!!

        assertTrue("파일이 실제로 있으니 true여야 함", repository.bookFileExists(book))

        file.delete()

        assertFalse("파일이 삭제됐으니 false여야 함 — '이어서 읽기' 후보에서 빠지는 경로", repository.bookFileExists(book))
    }
}

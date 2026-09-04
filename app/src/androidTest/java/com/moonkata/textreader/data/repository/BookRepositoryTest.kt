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
 * `BookRepository` has only ever been exercised indirectly across various other tests — here,
 * `findOrCreateBook`'s upsert branches (new/existing/relativePath update) and
 * `openBookContent`/`bookFileExists`, which involve real file I/O, are targeted directly.
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

        assertEquals("The id must stay the same (must not create a new one)", id, idAfterMove)
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

        assertTrue("Must be true since the file genuinely exists", repository.bookFileExists(book))

        file.delete()

        assertFalse("Must be false since the file was deleted — the path that drops it from 'continue reading' candidates", repository.bookFileExists(book))
    }
}

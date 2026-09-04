package com.moonkata.textreader.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Targets `BookDao` directly with an in-memory Room DB — until now it was only ever exercised indirectly through other tests. */
@RunWith(AndroidJUnit4::class)
class BookDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao

    private fun book(uri: String, addedAt: Long = 1_000L, lastOpenedAt: Long? = null) = BookEntity(
        documentUri = uri,
        displayName = "책 $uri",
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
    )

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        dao = db.bookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertThenGetById_returnsTheInsertedBook() = runBlocking {
        val id = dao.insert(book("file:///a.txt"))
        val loaded = dao.getById(id).first()
        assertEquals("file:///a.txt", loaded?.documentUri)
    }

    @Test
    fun getById_unknownId_returnsNull() = runBlocking {
        assertNull(dao.getById(999_999L).first())
    }

    @Test
    fun findByUri_matchesOnDocumentUri_andIsNullWhenNotFound() = runBlocking {
        dao.insert(book("file:///found.txt"))
        assertEquals("file:///found.txt", dao.findByUri("file:///found.txt")?.documentUri)
        assertNull(dao.findByUri("file:///missing.txt"))
    }

    @Test
    fun insertingADuplicateDocumentUri_isIgnored_dueToTheUniqueIndex() = runBlocking {
        val firstId = dao.insert(book("file:///dup.txt", addedAt = 1_000L))
        val secondId = dao.insert(book("file:///dup.txt", addedAt = 2_000L))

        assertEquals("Since OnConflictStrategy.IGNORE is used, the second insert must return -1", -1L, secondId)
        val stored = dao.findByUri("file:///dup.txt")
        assertEquals("The original row must remain as-is (must not be overwritten with the second addedAt)", firstId, stored?.id)
        assertEquals(1_000L, stored?.addedAt)
    }

    @Test
    fun updateReadPosition_onlyChangesOffsetProgressAndTimestamp() = runBlocking {
        val id = dao.insert(book("file:///pos.txt"))
        dao.updateReadPosition(id, offset = 1234, progress = 0.5f, timestamp = 9_999L)

        val updated = dao.getById(id).first()
        assertEquals(1234, updated?.lastReadCharOffset)
        assertEquals(0.5f, updated?.lastReadProgressPercent)
        assertEquals(9_999L, updated?.lastOpenedAt)
        assertEquals("Untouched fields must remain unchanged", "책 file:///pos.txt", updated?.displayName)
    }

    @Test
    fun updateMeta_onlySetsTotalCharCountAndEncoding() = runBlocking {
        val id = dao.insert(book("file:///meta.txt"))
        dao.updateMeta(id, totalCharCount = 5_000, encoding = "UTF-8")

        val updated = dao.getById(id).first()
        assertEquals(5_000, updated?.totalCharCount)
        assertEquals("UTF-8", updated?.detectedEncoding)
    }

    @Test
    fun updateRelativePath_onlySetsRelativePath() = runBlocking {
        val id = dao.insert(book("file:///rel.txt"))
        dao.updateRelativePath(id, "folder/rel.txt")

        assertEquals("folder/rel.txt", dao.getById(id).first()?.relativePath)
    }

    @Test
    fun delete_removesTheRow() = runBlocking {
        val entity = book("file:///todelete.txt")
        val id = dao.insert(entity)
        dao.delete(entity.copy(id = id))

        assertNull(dao.getById(id).first())
    }

    @Test
    fun getAllOrderByRecent_ordersByLastOpenedAtDescThenAddedAtDesc_neverOpenedBooksSortLast() = runBlocking {
        dao.insert(book("file:///never-opened-old.txt", addedAt = 1_000L, lastOpenedAt = null))
        dao.insert(book("file:///opened-early.txt", addedAt = 2_000L, lastOpenedAt = 5_000L))
        dao.insert(book("file:///opened-recent.txt", addedAt = 3_000L, lastOpenedAt = 9_000L))
        dao.insert(book("file:///never-opened-new.txt", addedAt = 4_000L, lastOpenedAt = null))

        val order = dao.getAllOrderByRecent().first().map { it.documentUri }

        assertEquals(
            listOf(
                "file:///opened-recent.txt",
                "file:///opened-early.txt",
                "file:///never-opened-new.txt",
                "file:///never-opened-old.txt",
            ),
            order,
        )
    }

    @Test
    fun getAllOrderByRecent_emitsAgainWhenDataChanges() = runBlocking {
        val emissions = mutableListOf<Int>()
        val job = launch {
            dao.getAllOrderByRecent().collect { emissions.add(it.size) }
        }
        // Wait briefly until the Flow subscription actually starts.
        var waited = 0
        while (emissions.isEmpty() && waited < 2_000) {
            delay(20)
            waited += 20
        }
        dao.insert(book("file:///reactive.txt"))
        waited = 0
        while (emissions.size < 2 && waited < 2_000) {
            delay(20)
            waited += 20
        }
        job.cancel()

        assertTrue("The Flow must emit the new list again after an insert", emissions.size >= 2)
        assertEquals(0, emissions.first())
        assertEquals(1, emissions.last())
    }
}

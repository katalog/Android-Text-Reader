package com.moonkata.textreader.data.repository

import android.content.Context
import com.moonkata.textreader.data.db.BookDao
import com.moonkata.textreader.data.db.BookEntity
import com.moonkata.textreader.data.file.BookContentReader
import com.moonkata.textreader.data.file.BookSource
import kotlinx.coroutines.flow.Flow

class BookRepository(
    private val context: Context,
    private val bookDao: BookDao,
) {
    fun observeLibrary(): Flow<List<BookEntity>> = bookDao.getAllOrderByRecent()

    fun observeBook(bookId: Long): Flow<BookEntity?> = bookDao.getById(bookId)

    /**
     * Called when a file is tapped in the folder view — returns the existing record if it's already
     * been opened before, or creates a new one on first open.
     * [relativePath] is the VSCode sync matching key (§3) — an empty string when it can't be computed
     * (e.g. a file inside a zip). Even for an already-registered book, if relativePath differs (it was
     * empty, or the folder was moved), it's updated on the spot — the design lets it fill in naturally
     * on revisit instead of a forced backfill (§Open Question 6).
     */
    suspend fun findOrCreateBook(source: BookSource, displayName: String, sizeBytes: Long, relativePath: String = ""): Long {
        val storedUri = source.toStoredString()
        bookDao.findByUri(storedUri)?.let { existing ->
            if (relativePath.isNotEmpty() && existing.relativePath != relativePath) {
                bookDao.updateRelativePath(existing.id, relativePath)
            }
            return existing.id
        }
        val insertedId = bookDao.insert(
            BookEntity(
                documentUri = storedUri,
                displayName = displayName,
                fileSizeBytes = sizeBytes,
                addedAt = System.currentTimeMillis(),
                relativePath = relativePath,
            ),
        )
        return if (insertedId != -1L) insertedId else bookDao.findByUri(storedUri)!!.id
    }

    suspend fun openBookContent(book: BookEntity): BookContentReader.ReadResult {
        val source = BookSource.fromStoredString(book.documentUri)
        return BookContentReader.read(context, source)
    }

    /** Checks whether [book]'s actual file can be opened right now — false if it was deleted/moved or SAF permission was revoked. */
    suspend fun bookFileExists(book: BookEntity): Boolean {
        val source = BookSource.fromStoredString(book.documentUri)
        return BookContentReader.exists(context, source)
    }

    suspend fun markOpened(bookId: Long, totalCharCount: Int, encoding: String) {
        bookDao.updateMeta(bookId, totalCharCount, encoding)
    }

    /** Fallback backfill for relativePath when a book is loaded without going through library browsing (e.g. resume reading). */
    suspend fun updateRelativePath(bookId: Long, relativePath: String) {
        bookDao.updateRelativePath(bookId, relativePath)
    }

    suspend fun updateReadPosition(bookId: Long, offset: Int, progressPercent: Float) {
        bookDao.updateReadPosition(bookId, offset, progressPercent, System.currentTimeMillis())
    }

    suspend fun deleteBook(book: BookEntity) = bookDao.delete(book)
}

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

    /** 폴더뷰에서 파일을 탭했을 때 호출 — 이미 열어본 적 있으면 기존 기록을, 처음이면 새 기록을 반환. */
    suspend fun findOrCreateBook(source: BookSource, displayName: String, sizeBytes: Long): Long {
        val storedUri = source.toStoredString()
        bookDao.findByUri(storedUri)?.let { return it.id }
        val insertedId = bookDao.insert(
            BookEntity(
                documentUri = storedUri,
                displayName = displayName,
                fileSizeBytes = sizeBytes,
                addedAt = System.currentTimeMillis(),
            ),
        )
        return if (insertedId != -1L) insertedId else bookDao.findByUri(storedUri)!!.id
    }

    suspend fun openBookContent(book: BookEntity): BookContentReader.ReadResult {
        val source = BookSource.fromStoredString(book.documentUri)
        return BookContentReader.read(context, source)
    }

    /** [book]의 실제 파일을 지금 열 수 있는지 확인 — 삭제/이동되었거나 SAF 권한이 회수됐으면 false. */
    suspend fun bookFileExists(book: BookEntity): Boolean {
        val source = BookSource.fromStoredString(book.documentUri)
        return BookContentReader.exists(context, source)
    }

    suspend fun markOpened(bookId: Long, totalCharCount: Int, encoding: String) {
        bookDao.updateMeta(bookId, totalCharCount, encoding)
    }

    suspend fun updateReadPosition(bookId: Long, offset: Int, progressPercent: Float) {
        bookDao.updateReadPosition(bookId, offset, progressPercent, System.currentTimeMillis())
    }

    suspend fun deleteBook(book: BookEntity) = bookDao.delete(book)
}

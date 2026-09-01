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
     * 폴더뷰에서 파일을 탭했을 때 호출 — 이미 열어본 적 있으면 기존 기록을, 처음이면 새 기록을 반환.
     * [relativePath]는 VSCode 동기화 매칭 키(§3) — zip 안 파일 등 계산 불가한 경우 빈 문자열.
     * 이미 등록된 책이라도 relativePath가 다르면(비어있었거나 폴더가 옮겨진 경우) 그때그때 갱신한다 —
     * 강제 백필 없이 재방문 시 자연스럽게 채워지도록 하는 설계(§열린 질문 6).
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

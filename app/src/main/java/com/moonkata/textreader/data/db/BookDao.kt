package com.moonkata.textreader.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC, addedAt DESC")
    fun getAllOrderByRecent(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun getById(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE documentUri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Query("UPDATE books SET lastReadCharOffset = :offset, lastReadProgressPercent = :progress, lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun updateReadPosition(id: Long, offset: Int, progress: Float, timestamp: Long)

    @Query("UPDATE books SET totalCharCount = :totalCharCount, detectedEncoding = :encoding WHERE id = :id")
    suspend fun updateMeta(id: Long, totalCharCount: Int, encoding: String)

    @Query("UPDATE books SET relativePath = :relativePath WHERE id = :id")
    suspend fun updateRelativePath(id: Long, relativePath: String)

    @Delete
    suspend fun delete(book: BookEntity)
}

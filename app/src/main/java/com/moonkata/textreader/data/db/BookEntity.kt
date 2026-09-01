package com.moonkata.textreader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [Index(value = ["documentUri"], unique = true)],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentUri: String,
    val displayName: String,
    val detectedEncoding: String = "",
    val totalCharCount: Int = 0,
    val lastReadCharOffset: Int = 0,
    val lastReadProgressPercent: Float = 0f,
    val fileSizeBytes: Long = 0,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
)

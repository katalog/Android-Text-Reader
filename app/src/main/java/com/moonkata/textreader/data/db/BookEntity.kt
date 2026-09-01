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
    /** 동기화 루트 폴더 기준 상대 경로(정규화 완료 — VSCode 읽기 위치 동기화 매칭 키). 비어있으면
     * 아직 계산 안 된 책(마이그레이션 이전에 등록됨)이거나 zip 안의 파일이라 동기화 대상이 아님. */
    val relativePath: String = "",
)

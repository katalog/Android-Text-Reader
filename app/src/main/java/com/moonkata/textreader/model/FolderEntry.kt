package com.moonkata.textreader.model

import android.net.Uri
import com.moonkata.textreader.data.file.BookSource

/** 폴더뷰 한 행 — 하위 폴더, zip 아카이브, 또는 열 수 있는 txt 파일. */
sealed class FolderEntry {
    abstract val name: String

    data class Folder(override val name: String, val uri: Uri) : FolderEntry()

    data class ZipArchive(
        override val name: String,
        val uri: Uri,
        val sizeBytes: Long,
        val lastModified: Long,
    ) : FolderEntry()

    data class TextFile(
        override val name: String,
        val source: BookSource,
        val sizeBytes: Long,
        val lastModified: Long,
    ) : FolderEntry()
}

enum class FolderSortOption(val label: String) {
    NAME_ASC("이름순"),
    NAME_DESC("이름 역순"),
    DATE_DESC("최근 수정순"),
    DATE_ASC("오래된 수정순"),
    SIZE_DESC("큰 용량순"),
    SIZE_ASC("작은 용량순"),
}

package com.moonkata.textreader.model

import android.net.Uri
import androidx.annotation.StringRes
import com.moonkata.textreader.R
import com.moonkata.textreader.data.file.BookSource

/** One row in the folder view — a subfolder, a zip archive, or an openable txt file. */
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

enum class FolderSortOption(@StringRes val labelRes: Int) {
    NAME_ASC(R.string.sort_name_asc),
    NAME_DESC(R.string.sort_name_desc),
    DATE_DESC(R.string.sort_date_desc),
    DATE_ASC(R.string.sort_date_asc),
    SIZE_DESC(R.string.sort_size_desc),
    SIZE_ASC(R.string.sort_size_asc),
}

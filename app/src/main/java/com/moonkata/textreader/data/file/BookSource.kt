package com.moonkata.textreader.data.file

import android.net.Uri

/** 책 하나의 실제 파일 위치 — 순수 txt 파일이거나 zip 안에 들어있는 entry. */
sealed class BookSource {
    data class PlainTxt(val uri: Uri) : BookSource()
    data class ZipEntryTxt(val zipUri: Uri, val entryName: String) : BookSource()

    fun toStoredString(): String = when (this) {
        is PlainTxt -> uri.toString()
        is ZipEntryTxt -> "zip:$zipUri!$entryName"
    }

    companion object {
        fun fromStoredString(stored: String): BookSource {
            return if (stored.startsWith("zip:")) {
                val rest = stored.removePrefix("zip:")
                val separatorIndex = rest.lastIndexOf('!')
                val zipUri = Uri.parse(rest.substring(0, separatorIndex))
                val entryName = rest.substring(separatorIndex + 1)
                ZipEntryTxt(zipUri, entryName)
            } else {
                PlainTxt(Uri.parse(stored))
            }
        }
    }
}

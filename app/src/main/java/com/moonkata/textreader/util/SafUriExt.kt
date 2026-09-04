package com.moonkata.textreader.util

import android.content.Context
import android.content.Intent
import android.net.Uri

fun Context.takePersistableReadPermission(uri: Uri) {
    try {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    } catch (e: SecurityException) {
        // URI was already released or the permission can't be granted — ignore and continue
    }
}

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
        // 이미 해제되었거나 부여할 수 없는 URI — 무시하고 넘어감
    }
}

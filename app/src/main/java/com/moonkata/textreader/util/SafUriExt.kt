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

/**
 * True only if this URI still has a persisted read grant. A URI string surviving in DataStore
 * (e.g. restored by Android's Auto Backup on a fresh install) doesn't imply the grant survived —
 * SAF permissions are re-issued per install, so a restored URI can look valid while being
 * completely unusable. Check this before treating a saved tree URI as openable.
 */
fun Context.hasPersistedReadPermission(uri: Uri): Boolean =
    contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

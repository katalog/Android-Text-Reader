package com.moonkata.textreader.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** A single sync-eligible file inside the local library's SAF tree — [relativePath] is `/`-separated, rooted at the library root. */
data class LocalLibraryFile(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val documentUri: Uri,
)

/**
 * For PC sync (.docs/PC_SYNC_SERVER_PLAN.md §3) delta computation — recursively walks the entire library
 * SAF tree and lists only `.txt`/`.zip` files. `SafFolderBrowser`'s `listFolder` is meant for the folder
 * view to show one level at a time, so it doesn't recurse; this is kept separate because sync needs the
 * whole tree at once.
 */
class LocalLibraryScanner(private val context: Context) {

    fun scanRecursively(treeUri: Uri): List<LocalLibraryFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<LocalLibraryFile>()
        walk(root, "", result)
        return result
    }

    private fun walk(dir: DocumentFile, relativePrefix: String, out: MutableList<LocalLibraryFile>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val childRelativePath = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
            if (child.isDirectory) {
                walk(child, childRelativePath, out)
            } else if (name.endsWith(".txt", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)) {
                out += LocalLibraryFile(
                    relativePath = childRelativePath,
                    sizeBytes = child.length(),
                    lastModifiedMillis = child.lastModified(),
                    documentUri = child.uri,
                )
            }
        }
    }
}

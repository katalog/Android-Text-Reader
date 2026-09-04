package com.moonkata.textreader.data.file

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.moonkata.textreader.R
import com.moonkata.textreader.model.FolderEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/**
 * The capability a folder browser needs to provide — split out as an interface so the real SAF-backed
 * implementation (`SafFolderBrowser`) and a fake that returns a fixed list without real SAF
 * permissions (for tests) can be swapped in interchangeably.
 */
interface FolderBrowser {
    fun rootDisplayName(treeUri: Uri): String
    suspend fun listFolder(folderUri: Uri): List<FolderEntry>
    suspend fun listZipEntries(zipUri: Uri): List<FolderEntry.TextFile>
}

/** Lists an SAF tree one folder level at a time — queries only the current location's children instead of a full recursive scan. */
class SafFolderBrowser(private val context: Context) : FolderBrowser {

    override fun rootDisplayName(treeUri: Uri): String = DocumentFile.fromTreeUri(context, treeUri)?.name ?: context.getString(R.string.folder_root_fallback_name)

    override suspend fun listFolder(folderUri: Uri): List<FolderEntry> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        val entries = mutableListOf<FolderEntry>()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            when {
                child.isDirectory -> entries += FolderEntry.Folder(name, child.uri)
                name.endsWith(".txt", ignoreCase = true) -> entries += FolderEntry.TextFile(
                    name = name,
                    source = BookSource.PlainTxt(child.uri),
                    sizeBytes = child.length(),
                    lastModified = child.lastModified(),
                )
                name.endsWith(".zip", ignoreCase = true) -> entries += FolderEntry.ZipArchive(
                    name = name,
                    uri = child.uri,
                    sizeBytes = child.length(),
                    lastModified = child.lastModified(),
                )
            }
        }
        entries
    }

    override suspend fun listZipEntries(zipUri: Uri): List<FolderEntry.TextFile> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<FolderEntry.TextFile>()
        try {
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (!entry.isDirectory && entryName.endsWith(".txt", ignoreCase = true)) {
                            entries += FolderEntry.TextFile(
                                name = entryName.substringAfterLast('/'),
                                source = BookSource.ZipEntryTxt(zipUri, entryName),
                                sizeBytes = entry.size.takeIf { it >= 0 } ?: 0L,
                                lastModified = entry.time.takeIf { it >= 0 } ?: 0L,
                            )
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            // Corrupted or unsupported zip — treat as an empty list
        }
        entries
    }
}

package com.moonkata.textreader.ui.library

import android.net.Uri
import com.moonkata.textreader.data.file.FolderBrowser
import com.moonkata.textreader.model.FolderEntry

/**
 * A test double that, without real SAF permissions or a system folder picker, just returns a
 * predetermined folder-to-listing mapping. The real file (`BookSource`) that `FolderEntry.TextFile`
 * points to needs to be a genuinely readable path (e.g. a `file://` URI for a file the test wrote
 * itself) in order to verify scenarios that carry through to the reader.
 */
class FakeFolderBrowser(
    private val entriesByLocation: Map<Uri, List<FolderEntry>>,
    private val zipEntriesByUri: Map<Uri, List<FolderEntry.TextFile>> = emptyMap(),
) : FolderBrowser {
    override fun rootDisplayName(treeUri: Uri): String = "테스트 폴더"

    override suspend fun listFolder(folderUri: Uri): List<FolderEntry> = entriesByLocation[folderUri].orEmpty()

    override suspend fun listZipEntries(zipUri: Uri): List<FolderEntry.TextFile> = zipEntriesByUri[zipUri].orEmpty()
}

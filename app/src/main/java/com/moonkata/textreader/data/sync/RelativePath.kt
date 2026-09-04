package com.moonkata.textreader.data.sync

import android.net.Uri
import android.provider.DocumentsContract
import java.text.Normalizer

/**
 * Matching-key normalization rule for VSCode reading-position sync (.docs/VSCODE_SYNC_PLAN.md §3).
 * Order: unify separators → NFC normalize → lowercase — the VSCode extension must apply the exact same
 * order for matching to be consistent.
 */
fun normalizeRelativePath(rawSegments: List<String>): String {
    val joined = rawSegments.joinToString("/")
    return Normalizer.normalize(joined.replace('\\', '/'), Normalizer.Form.NFC).lowercase()
}

/**
 * `LibraryViewModel` only computes and passes along a relativePath while browsing folders (the
 * BrowseLocation stack), but it was found in actual use that paths like the "continue reading" dialog or
 * reloading an already-registered book don't go through that stack, leaving relativePath perpetually empty
 * (a follow-up to §Open Question 6 — contrary to the assumption that this was "a niche revisit case,"
 * "continue reading" turned out to be the most common entry path instead). This is a fallback that
 * reverse-derives relativePath by exploiting the fact that an SAF document URI's documentId string is
 * usually hierarchical, in the form "primary:folder/subfolder/file.txt" — stripping the saved tree root's
 * documentId off as a prefix. It's a heuristic that relies on the document provider using hierarchical IDs
 * (generally true for local storage providers), so it's not a 100% guarantee, but a failure just returns
 * null and doesn't affect existing behavior.
 */
fun relativePathFromSafDocumentUri(documentUri: Uri, treeUri: Uri): String? {
    return try {
        val documentId = DocumentsContract.getDocumentId(documentUri)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        // Compare against the "$treeDocumentId/" prefix, including the separator — a plain
        // startsWith(treeDocumentId) would, when the tree is "primary:Books", also incorrectly match a
        // document from the sibling tree "primary:BooksExtra" due to the overlapping prefix.
        val prefix = "$treeDocumentId/"
        if (!documentId.startsWith(prefix)) return null
        val relative = documentId.removePrefix(prefix)
        if (relative.isEmpty()) return null
        normalizeRelativePath(relative.split("/"))
    } catch (e: Exception) {
        null
    }
}

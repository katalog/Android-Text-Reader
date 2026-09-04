package com.moonkata.textreader.data.sync

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [relativePathFromSafDocumentUri] relies on `DocumentsContract.getDocumentId`/`getTreeDocumentId`
 * (pure URI path-segment parsing, no actual content-provider calls), so it could be verified with an
 * instrumented test even without file I/O — but that parsing itself doesn't work against the
 * android.jar stubs, so it lives here rather than in app/src/test.
 */
@RunWith(AndroidJUnit4::class)
class RelativePathSafTest {

    private val treeUri: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABooks")

    private fun documentUriFor(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    @Test
    fun topLevelFile_resolvesToItsFileName() {
        val doc = documentUriFor("primary:Books/chapter1.txt")
        assertEquals("chapter1.txt", relativePathFromSafDocumentUri(doc, treeUri))
    }

    @Test
    fun nestedFile_resolvesToSlashSeparatedRelativePath() {
        val doc = documentUriFor("primary:Books/series/sub/chapter1.txt")
        assertEquals("series/sub/chapter1.txt", relativePathFromSafDocumentUri(doc, treeUri))
    }

    @Test
    fun documentEqualToTreeRoot_returnsNull() {
        val doc = documentUriFor("primary:Books")
        assertNull(relativePathFromSafDocumentUri(doc, treeUri))
    }

    @Test
    fun documentFromAnUnrelatedTree_returnsNull() {
        val otherTree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AOther")
        val doc = DocumentsContract.buildDocumentUriUsingTree(otherTree, "primary:Other/book.txt")
        assertNull(relativePathFromSafDocumentUri(doc, treeUri))
    }

    @Test
    fun siblingTreeSharingANamePrefix_isNotMistakenForTheSameTree() {
        // Regression test: comparing documents from the sibling tree "primary:BooksExtra" — whose
        // prefix overlaps with the "primary:Books" tree — using a plain string startsWith used to
        // match incorrectly (an unbounded prefix overlap). The comparison now includes the trailing
        // "/", so this should be null.
        val siblingTree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABooksExtra")
        val doc = DocumentsContract.buildDocumentUriUsingTree(siblingTree, "primary:BooksExtra/book.txt")
        assertNull(relativePathFromSafDocumentUri(doc, treeUri))
    }

    @Test
    fun result_isNormalizedLikeNormalizeRelativePath() {
        val doc = documentUriFor("primary:Books/Series/Chapter1.TXT")
        assertEquals("series/chapter1.txt", relativePathFromSafDocumentUri(doc, treeUri))
    }
}

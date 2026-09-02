package com.moonkata.textreader.data.sync

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [relativePathFromSafDocumentUri]는 `DocumentsContract.getDocumentId`/`getTreeDocumentId`(순수
 * URI 경로 세그먼트 파싱, 실제 콘텐츠 프로바이더 호출 없음)에 기대므로 파일 I/O 없이도 계측 테스트로
 * 검증 가능하지만, 이 파싱 자체가 android.jar 스텁에서는 동작하지 않아 app/src/test가 아니라 여기 둔다.
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
        // 회귀 테스트: "primary:Books" 트리와 접두사가 겹치는 형제 트리 "primary:BooksExtra"의 문서를
        // 단순 문자열 startsWith로 비교하면 잘못 매칭됐었다(구분자 없는 접두사 겹침) — 이제 "/"까지
        // 포함해 비교하므로 null이어야 한다.
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

package com.moonkata.textreader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** [relativePathFromSafDocumentUri]는 android.net.Uri/DocumentsContract가 필요해 androidTest 쪽
 * [RelativePathSafTest]에서 검증한다 — 여기는 순수 문자열 로직인 [normalizeRelativePath]만 다룬다. */
class RelativePathNormalizeTest {

    @Test
    fun `joins segments with forward slash`() {
        assertEquals("folder/book.txt", normalizeRelativePath(listOf("folder", "book.txt")))
    }

    @Test
    fun `unifies backslash separators to forward slash`() {
        assertEquals("folder/sub/book.txt", normalizeRelativePath(listOf("folder\\sub\\book.txt")))
    }

    @Test
    fun `lowercases so matching is case-insensitive`() {
        assertEquals("folder/book.txt", normalizeRelativePath(listOf("Folder", "Book.TXT")))
    }

    @Test
    fun `NFC-normalizes so decomposed and precomposed Hangul produce the same key`() {
        // U+AC01("각")은 완성형 단일 코드포인트. U+1100+U+1161+U+11A8은 초성/중성/종성 자모 3개로
        // 분해된 형태 — 렌더링하면 똑같이 보이지만 바이트 표현은 다른 별개의 문자열이다. 안드로이드/
        // PC/VSCode가 서로 다른 정규화 형태로 파일명을 줄 수 있어(특히 macOS는 분해형을 씀) NFC로
        // 통일해야 같은 파일에 대해 항상 같은 비교 키가 나온다. 소스에 리터럴 한글을 직접 쓰면 편집
        // 과정에서 정규화 형태가 조용히 바뀔 위험이 있어, 두 형태 다 유니코드 이스케이프로 명시했다.
        val precomposed = "각.txt"
        val decomposed = "각.txt"
        assertEquals(normalizeRelativePath(listOf(precomposed)), normalizeRelativePath(listOf(decomposed)))
        assertEquals("각.txt", normalizeRelativePath(listOf(decomposed)))
    }

    @Test
    fun `single segment with no separator is unchanged aside from case`() {
        assertEquals("book.txt", normalizeRelativePath(listOf("BOOK.txt")))
    }
}

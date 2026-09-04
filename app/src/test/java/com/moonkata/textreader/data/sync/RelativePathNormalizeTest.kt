package com.moonkata.textreader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** [relativePathFromSafDocumentUri] needs android.net.Uri/DocumentsContract, so it's verified on
 * the androidTest side in [RelativePathSafTest] — here we only cover the pure string logic in
 * [normalizeRelativePath]. */
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
        // U+AC01 ("각") is a single precomposed codepoint. U+1100+U+1161+U+11A8 is the same
        // character decomposed into 3 separate leading/vowel/trailing jamo — they render
        // identically but are distinct strings at the byte level. Android/PC/VSCode can hand us
        // filenames in different normalization forms (macOS in particular uses the decomposed
        // form), so normalizing to NFC is what guarantees the same comparison key for the same
        // file every time. Writing literal Hangul directly in source risks the normalization form
        // silently changing during editing, so both forms are spelled out explicitly as Unicode
        // escapes.
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

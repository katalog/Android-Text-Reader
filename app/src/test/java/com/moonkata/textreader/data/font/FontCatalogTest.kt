package com.moonkata.textreader.data.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A self-check that catches copy-paste mistakes producing duplicate ids/filenames when a new
 * entry is added to the font list. Has no Android dependency, so it's a plain JUnit test.
 */
class FontCatalogTest {

    @Test
    fun everyEntry_hasAUniqueId() {
        val ids = FontCatalog.entries.map { it.id }

        assertEquals("No entry should have a duplicate id", ids.size, ids.toSet().size)
    }

    @Test
    fun everyEntry_hasAUniqueLocalFileName() {
        val fileNames = FontCatalog.entries.map { it.localFileName }

        assertEquals("A duplicate localFileName would cause different fonts to overwrite the same file", fileNames.size, fileNames.toSet().size)
    }

    @Test
    fun systemDefaultId_doesNotCollideWithAnyCatalogEntry() {
        assertNull(FontCatalog.findById(FontCatalog.SYSTEM_DEFAULT_ID))
    }

    @Test
    fun findById_returnsTheMatchingEntry() {
        val entry = FontCatalog.entries.first()

        assertEquals(entry, FontCatalog.findById(entry.id))
    }

    @Test
    fun findById_returnsNullForAnUnknownId() {
        assertNull(FontCatalog.findById("존재하지 않는 id"))
    }
}

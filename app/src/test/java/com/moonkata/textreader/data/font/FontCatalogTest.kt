package com.moonkata.textreader.data.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 폰트 목록에 새 항목을 추가할 때 복붙 실수로 id/파일명이 겹치는 걸 잡아주는 자기 검증.
 * Android 의존성이 없어 일반 JUnit으로 둔다.
 */
class FontCatalogTest {

    @Test
    fun everyEntry_hasAUniqueId() {
        val ids = FontCatalog.entries.map { it.id }

        assertEquals("id가 중복되는 항목이 있으면 안 됨", ids.size, ids.toSet().size)
    }

    @Test
    fun everyEntry_hasAUniqueLocalFileName() {
        val fileNames = FontCatalog.entries.map { it.localFileName }

        assertEquals("localFileName이 중복되면 서로 다른 폰트가 같은 파일을 덮어쓰게 됨", fileNames.size, fileNames.toSet().size)
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

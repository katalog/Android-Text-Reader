package com.moonkata.textreader.data.sync

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [computeSyncDelta] 회귀 테스트 — 2026-09-02 실기기 검증에서 잡힌 버그(로컬 파일의 수정시각이
 * "받은 시점"이 되어 재동기화 때마다 안 바뀐 파일까지 매번 다시 받던 문제, PcSyncFileManager.kt 주석
 * 참고)의 재발을 막는 게 핵심 목적이다: 크기만 같으면 수정시각이 달라도 다시 받으면 안 된다.
 *
 * 계산 자체는 파일 I/O가 없는 순수 로직이지만, [LocalLibraryFile.documentUri]가 `android.net.Uri`
 * 타입이라(값 자체는 이 계산에서 안 읽음) `Uri.parse`가 진짜로 동작하는 계측 테스트 쪽에 둔다 —
 * app/src/test의 android.jar 스텁에서는 `Uri.parse`가 예외를 던진다.
 */
class PcSyncDeltaTest {

    // Uri는 델타 계산에서 실제로 읽지 않는 필드라 아무 인스턴스나 하나면 충분 — 값 자체는 무의미.
    private val fakeUri: Uri = Uri.parse("content://fake")

    private fun remote(path: String, size: Long, modified: Long = 1_000L) =
        PcRemoteFile(relativePath = path, sizeBytes = size, lastModifiedMillis = modified)

    private fun local(path: String, size: Long, modified: Long = 9_999L) =
        LocalLibraryFile(relativePath = path, sizeBytes = size, lastModifiedMillis = modified, documentUri = fakeUri)

    @Test
    fun fileOnlyOnRemote_isWrittenAsNewDownload() {
        val delta = computeSyncDelta(
            remoteFiles = listOf(remote("book.txt", size = 100)),
            localFiles = emptyList(),
        )
        assertEquals(listOf("book.txt"), delta.toWrite.map { it.relativePath })
        assertTrue(delta.toDelete.isEmpty())
    }

    @Test
    fun fileOnlyOnLocal_isDeleted() {
        val delta = computeSyncDelta(
            remoteFiles = emptyList(),
            localFiles = listOf(local("stale.txt", size = 100)),
        )
        assertTrue(delta.toWrite.isEmpty())
        assertEquals(listOf("stale.txt"), delta.toDelete.map { it.relativePath })
    }

    @Test
    fun sameSize_isLeftUntouched_evenWhenLocalModificationTimeDiffersWildly() {
        // 이 테스트가 바로 그 회귀를 지킨다: 로컬 mtime(9999)과 원격 mtime(1000)이 완전히 다르지만
        // 크기가 같으므로 다시 받으면 안 된다.
        val delta = computeSyncDelta(
            remoteFiles = listOf(remote("book.txt", size = 12_345, modified = 1_000L)),
            localFiles = listOf(local("book.txt", size = 12_345, modified = 9_999_999L)),
        )
        assertTrue("size가 같으면 toWrite에 들어가면 안 된다", delta.toWrite.isEmpty())
        assertTrue(delta.toDelete.isEmpty())
    }

    @Test
    fun differentSize_isReDownloadedAsUpdate_regardlessOfModificationTime() {
        val delta = computeSyncDelta(
            remoteFiles = listOf(remote("book.txt", size = 200)),
            localFiles = listOf(local("book.txt", size = 100)),
        )
        assertEquals(listOf("book.txt"), delta.toWrite.map { it.relativePath })
        assertTrue(delta.toDelete.isEmpty())
    }

    @Test
    fun matchingKeys_areCaseAndSeparatorInsensitive_viaNormalizeRelativePath() {
        val delta = computeSyncDelta(
            remoteFiles = listOf(remote("Folder/Book.txt", size = 100)),
            localFiles = listOf(local("folder\\book.txt", size = 100)),
        )
        assertTrue("정규화 키가 같으면 크기도 같으니 안 건드려야 한다", delta.toWrite.isEmpty())
        assertTrue(delta.toDelete.isEmpty())
    }

    @Test
    fun unrelatedFiles_areIndependentlyClassifiedInOnePass() {
        val delta = computeSyncDelta(
            remoteFiles = listOf(
                remote("unchanged.txt", size = 100),
                remote("changed.txt", size = 200),
                remote("new.txt", size = 300),
            ),
            localFiles = listOf(
                local("unchanged.txt", size = 100),
                local("changed.txt", size = 999),
                local("removed.txt", size = 400),
            ),
        )
        assertEquals(setOf("changed.txt", "new.txt"), delta.toWrite.map { it.relativePath }.toSet())
        assertEquals(setOf("removed.txt"), delta.toDelete.map { it.relativePath }.toSet())
    }

    @Test
    fun emptyRemoteAndEmptyLocal_produceEmptyDelta() {
        val delta = computeSyncDelta(remoteFiles = emptyList(), localFiles = emptyList())
        assertTrue(delta.toWrite.isEmpty())
        assertTrue(delta.toDelete.isEmpty())
    }

    // --- sinceMillis: 크기는 같지만 마지막 동기화 이후 원격에서 내용이 바뀐 파일 감지 ---

    @Test
    fun sameSize_butRemoteModifiedAfterSinceMillis_isReDownloaded() {
        // 오탈자 한 글자를 다른 글자로 바꾸는 등 글자 수는 그대로인 내용 수정 — 크기 비교만으로는
        // 영원히 못 잡으므로 sinceMillis(마지막 동기화 완료 시각)로 보완한다.
        val delta = computeSyncDelta(
            remoteFiles = listOf(remote("book.txt", size = 100, modified = 5_000L)),
            localFiles = listOf(local("book.txt", size = 100)),
            sinceMillis = 4_000L,
        )
        assertEquals(listOf("book.txt"), delta.toWrite.map { it.relativePath })
    }

    @Test
    fun sameSize_remoteModifiedBeforeSinceMillis_isLeftUntouched() {
        val delta = computeSyncDelta(
            remoteFiles = listOf(remote("book.txt", size = 100, modified = 3_000L)),
            localFiles = listOf(local("book.txt", size = 100)),
            sinceMillis = 4_000L,
        )
        assertTrue(delta.toWrite.isEmpty())
    }

    @Test
    fun sameSize_sinceMillisNull_isLeftUntouched_evenIfRemoteModifiedTimeIsRecent() {
        // sinceMillis를 안 넘기면(한 번도 동기화한 적 없는 등) 이 보정 자체를 건너뛰어야 한다 —
        // 크기만 보는 기존 회귀 방지 테스트들과 동일한 동작이 기본값이어야 함.
        val delta = computeSyncDelta(
            remoteFiles = listOf(remote("book.txt", size = 100, modified = 9_999_999L)),
            localFiles = listOf(local("book.txt", size = 100)),
            sinceMillis = null,
        )
        assertTrue(delta.toWrite.isEmpty())
    }
}

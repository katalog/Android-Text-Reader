package com.moonkata.textreader.data.sync

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [computeSyncDelta] — the main goal is to prevent a bug caught during
 * 2026-09-02 real-device verification (a local file's modification time became "the time it was
 * received," so unchanged files got re-downloaded on every resync; see the comment in
 * PcSyncFileManager.kt) from coming back: if the size matches, a differing modification time must
 * not trigger a re-download.
 *
 * The computation itself is pure logic with no file I/O, but since [LocalLibraryFile.documentUri]
 * is typed as `android.net.Uri` (its value is never actually read by this computation), this lives
 * in the instrumented tests where `Uri.parse` really works — the android.jar stub used by
 * app/src/test throws from `Uri.parse`.
 */
class PcSyncDeltaTest {

    // Uri is a field the delta computation never actually reads, so any single instance will do — its value is meaningless.
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
        // This is exactly the regression this test guards: the local mtime (9999) and the remote
        // mtime (1000) are completely different, but the size matches, so it must not be re-downloaded.
        val delta = computeSyncDelta(
            remoteFiles = listOf(remote("book.txt", size = 12_345, modified = 1_000L)),
            localFiles = listOf(local("book.txt", size = 12_345, modified = 9_999_999L)),
        )
        assertTrue("Must not end up in toWrite when the size matches", delta.toWrite.isEmpty())
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
        assertTrue("Must not be touched when the normalized keys match and the sizes match too", delta.toWrite.isEmpty())
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

    // --- sinceMillis: detect files that are the same size but changed remotely since the last sync ---

    @Test
    fun sameSize_butRemoteModifiedAfterSinceMillis_isReDownloaded() {
        // A content edit that keeps the same character count, like swapping one typo'd character
        // for another — size comparison alone could never catch this, so sinceMillis (the last
        // sync's completion time) fills the gap.
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
        // When sinceMillis isn't passed (e.g. never synced before), this correction must be
        // skipped entirely — the default behavior must match the earlier size-only regression tests.
        val delta = computeSyncDelta(
            remoteFiles = listOf(remote("book.txt", size = 100, modified = 9_999_999L)),
            localFiles = listOf(local("book.txt", size = 100)),
            sinceMillis = null,
        )
        assertTrue(delta.toWrite.isEmpty())
    }
}

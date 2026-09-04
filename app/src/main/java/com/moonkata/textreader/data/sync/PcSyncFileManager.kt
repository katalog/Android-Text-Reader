package com.moonkata.textreader.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Progress state for the single file currently being processed — the UI uses this for the "N / total" display. */
data class PcSyncProgress(val completed: Int, val total: Int, val currentRelativePath: String)

data class PcSyncResult(
    val downloaded: Int,
    val updated: Int,
    val deleted: Int,
    val failed: Int,
)

/** Result of [computeSyncDelta] — remote files that need to be received (created or overwritten), and local files that need to be deleted. */
data class PcSyncDelta(val toWrite: List<PcRemoteFile>, val toDelete: List<LocalLibraryFile>)

/**
 * Pure function that computes the sync delta from remote/local file listings alone — it does no I/O
 * (download/delete). Because this is one-way (PC → phone), local is always meant to mirror remote: if a
 * file exists only remotely, receive it (toWrite); if the size differs, receive it again (toWrite); if it
 * exists only locally, delete it (toDelete). Comparison keys are normalized with [normalizeRelativePath]
 * to match the case/Unicode/separator rules already validated for VSCode sync — however, the values held
 * in [PcSyncDelta] itself keep the original, un-normalized-case paths (the normalized key is comparison-only;
 * the actual file name must never be altered).
 *
 * Only size is compared, not local modification time — a downloaded local file's modification time
 * becomes "the moment it was received," not the original modification time on the PC (many SAF providers
 * don't allow a document's modification time to be set arbitrarily). Including local modification time in
 * the comparison was found in real usage to always differ between local and remote timestamps on every
 * resync (even when content was unchanged), causing every unchanged file to be re-downloaded every time.
 * For novel text files, a content change almost always changes the character count (= size) too, so size
 * alone is practically sufficient.
 *
 * That said, comparing size alone will forever miss "a content edit that keeps the same character count"
 * (e.g. replacing one typo character with another of the same length). Unlike local mtime, this is
 * compensated for by comparing the trustworthy [sinceMillis] (the time this device's own clock recorded
 * the last successful sync completion, not the PC's clock) against the remote-reported
 * [PcRemoteFile.lastModifiedMillis] — even if the size matches, if the remote modification time is after
 * that, it's treated as "changed on the PC since then" and re-downloaded. If null (never synced before),
 * this adjustment is not applied.
 */
fun computeSyncDelta(remoteFiles: List<PcRemoteFile>, localFiles: List<LocalLibraryFile>, sinceMillis: Long? = null): PcSyncDelta {
    val remoteByKey = remoteFiles.associateBy { syncDeltaKeyOf(it.relativePath) }
    val localByKey = localFiles.associateBy { syncDeltaKeyOf(it.relativePath) }

    val toWrite = remoteByKey.entries.filter { (key, remote) ->
        val local = localByKey[key]
        local == null ||
            local.sizeBytes != remote.sizeBytes ||
            (sinceMillis != null && remote.lastModifiedMillis > sinceMillis)
    }.map { it.value }
    val toDelete = localByKey.filterKeys { it !in remoteByKey }.values.toList()

    return PcSyncDelta(toWrite, toDelete)
}

private fun syncDeltaKeyOf(relativePath: String): String = normalizeRelativePath(relativePath.split("/"))

/**
 * PC tray server sync — connects delta computation ([computeSyncDelta]) with actually applying it to the
 * local (SAF) tree — .docs/PC_SYNC_SERVER_PLAN.md §3.
 */
class PcSyncFileManager(
    private val context: Context,
    private val client: PcSyncClient,
    private val localScanner: LocalLibraryScanner = LocalLibraryScanner(context),
) {
    /**
     * Returns null on failure (the remote listing itself couldn't be fetched, or [treeUri] is no longer
     * valid). Per-file failures are aggregated into [PcSyncResult.failed] instead — even if a single file
     * throws an SAF-side exception (e.g. a name collision while creating a folder, another entry
     * disappearing mid-traversal, or other unexpected provider-specific behavior), processing of the
     * remaining files and the overall sync result report must still proceed, so every per-file operation
     * is wrapped in [runCatching] — when several changes (folder moves/deletes/restructures) are mixed
     * into a single sync, one of them must not be allowed to crash the app.
     *
     * See [computeSyncDelta] for [sinceMillis] — passing the time this sync last completed successfully
     * (per this device's clock) causes files changed remotely after that time to be re-downloaded even if
     * their size matches.
     */
    suspend fun sync(treeUri: Uri, sinceMillis: Long? = null, onProgress: (PcSyncProgress) -> Unit = {}): PcSyncResult? = withContext(Dispatchers.IO) {
        val remoteFiles = client.listFilesRecursively() ?: return@withContext null
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull() ?: return@withContext null
        val localFiles = localScanner.scanRecursively(treeUri)
        val localByKey = localFiles.associateBy { syncDeltaKeyOf(it.relativePath) }

        val (toWrite, toDelete) = computeSyncDelta(remoteFiles, localFiles, sinceMillis)

        val total = toWrite.size + toDelete.size
        var completed = 0
        var downloaded = 0
        var updated = 0
        var deleted = 0
        var failed = 0

        for (remote in toWrite) {
            onProgress(PcSyncProgress(completed, total, remote.relativePath))
            val existingLocal = localByKey[syncDeltaKeyOf(remote.relativePath)]
            val success = runCatching {
                if (existingLocal != null) {
                    writeIntoExisting(existingLocal.documentUri, remote)
                } else {
                    writeNewFile(root, remote)
                }
            }.getOrDefault(false)
            when {
                !success -> failed++
                existingLocal != null -> updated++
                else -> downloaded++
            }
            completed++
        }

        for (local in toDelete) {
            onProgress(PcSyncProgress(completed, total, local.relativePath))
            val success = runCatching { DocumentFile.fromSingleUri(context, local.documentUri)?.delete() == true }.getOrDefault(false)
            if (success) {
                deleted++
                // Clean up any now-empty folder shells left behind when a whole folder was moved or
                // deleted on the PC — failing (because another file remains and it's not actually empty,
                // or the provider refuses the delete) doesn't affect the overall result.
                runCatching { pruneNowEmptyAncestors(root, local.relativePath) }
            } else {
                failed++
            }
            completed++
        }

        PcSyncResult(downloaded, updated, deleted, failed)
    }

    /** Updates an existing local file's contents in place while keeping its documentUri unchanged —
     * `BookEntity` keeps referencing the reading position via that URI, so deleting and recreating the
     * file would orphan that record. */
    private suspend fun writeIntoExisting(uri: Uri, remote: PcRemoteFile): Boolean {
        val output = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull() ?: return false
        return output.use { client.downloadFile(remote.relativePath, it) }
    }

    private suspend fun writeNewFile(root: DocumentFile, remote: PcRemoteFile): Boolean {
        val segments = remote.relativePath.split("/")
        val fileName = segments.last()
        val parent = resolveOrCreateFolder(root, segments.dropLast(1)) ?: return false
        val mime = if (fileName.endsWith(".zip", ignoreCase = true)) "application/zip" else "text/plain"
        val newFile = runCatching { parent.createFile(mime, fileName) }.getOrNull() ?: return false
        val output = runCatching { context.contentResolver.openOutputStream(newFile.uri, "wt") }.getOrNull() ?: return false
        return output.use { client.downloadFile(remote.relativePath, it) }
    }

    private fun resolveOrCreateFolder(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current = root
        for (segment in segments) {
            val existing = runCatching { current.findFile(segment) }.getOrNull()
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                runCatching { current.createDirectory(segment) }.getOrNull() ?: return null
            }
        }
        return current
    }

    /** Call this right after deleting the file at [deletedRelativePath] — if the folder that held it is
     * now empty, delete it too, and keep walking up to (but not including) [root] as long as each parent
     * folder is also empty. Only genuinely empty folders are deleted, so this stops as soon as any file
     * remains — this prevents empty folder shells from lingering locally after a "delete folder and its
     * files" or "restructure a subfolder" operation on the PC. */
    private fun pruneNowEmptyAncestors(root: DocumentFile, deletedRelativePath: String) {
        val folderSegments = deletedRelativePath.split("/").dropLast(1)
        if (folderSegments.isEmpty()) return

        val chain = mutableListOf(root)
        var current = root
        for (segment in folderSegments) {
            current = runCatching { current.findFile(segment) }.getOrNull()?.takeIf { it.isDirectory } ?: return
            chain += current
        }

        for (i in chain.lastIndex downTo 1) {
            val folder = chain[i]
            val isEmpty = runCatching { folder.listFiles().isEmpty() }.getOrDefault(false)
            if (!isEmpty) break
            if (!runCatching { folder.delete() }.getOrDefault(false)) break
        }
    }
}

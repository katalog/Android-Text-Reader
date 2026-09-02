package com.moonkata.textreader.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 지금 처리 중인 파일 하나에 대한 진행 상태 — UI가 "N / 전체" 표시에 쓴다. */
data class PcSyncProgress(val completed: Int, val total: Int, val currentRelativePath: String)

data class PcSyncResult(
    val downloaded: Int,
    val updated: Int,
    val deleted: Int,
    val failed: Int,
)

/** [computeSyncDelta]의 결과 — 받아야(새로 만들거나 덮어써야) 할 원격 파일과, 지워야 할 로컬 파일. */
data class PcSyncDelta(val toWrite: List<PcRemoteFile>, val toDelete: List<LocalLibraryFile>)

/**
 * 원격/로컬 파일 목록만으로 동기화 델타를 계산하는 순수 함수 — I/O(다운로드/삭제)는 하지 않는다.
 * 단방향(PC → 폰)이라 로컬은 항상 원격의 거울: 원격에만 있으면 받고(toWrite), 크기가 다르면 다시
 * 받고(toWrite), 로컬에만 있으면 지운다(toDelete). 비교 키는 [normalizeRelativePath]로 정규화해서
 * VSCode 동기화 때 이미 검증된 대소문자/유니코드/구분자 규칙과 일치시킨다 — 다만 [PcSyncDelta]에 담기는
 * 값 자체는 원본 대소문자 그대로의 경로다(정규화된 키는 비교 전용, 실제 파일명을 바꾸면 안 되므로).
 *
 * 크기만 비교하고 수정시각은 보지 않는다 — 다운로드한 로컬 파일의 수정시각은 "받은 시점"이 되지 PC
 * 원본의 수정시각을 그대로 못 물려받는다(SAF가 문서 수정시각을 임의로 설정하게 허용 안 하는 제공자가
 * 많음). 수정시각까지 비교 조건에 넣으면 재동기화 때마다 로컬 시각과 원격 시각이(내용은 그대로인데도)
 * 항상 달라서 안 바뀐 파일까지 매번 전부 다시 받는 문제가 실사용 중 확인됐다. 소설 텍스트 파일은 내용이
 * 바뀌면 거의 항상 글자 수(=크기)도 같이 바뀌니 크기만으로도 실용적으로 충분하다.
 */
fun computeSyncDelta(remoteFiles: List<PcRemoteFile>, localFiles: List<LocalLibraryFile>): PcSyncDelta {
    val remoteByKey = remoteFiles.associateBy { syncDeltaKeyOf(it.relativePath) }
    val localByKey = localFiles.associateBy { syncDeltaKeyOf(it.relativePath) }

    val toWrite = remoteByKey.entries.filter { (key, remote) ->
        val local = localByKey[key]
        local == null || local.sizeBytes != remote.sizeBytes
    }.map { it.value }
    val toDelete = localByKey.filterKeys { it !in remoteByKey }.values.toList()

    return PcSyncDelta(toWrite, toDelete)
}

private fun syncDeltaKeyOf(relativePath: String): String = normalizeRelativePath(relativePath.split("/"))

/**
 * PC 트레이 서버 동기화 — 델타 계산([computeSyncDelta])과 실제 로컬(SAF) 반영을 잇는다 —
 * .docs/PC_SYNC_SERVER_PLAN.md §3.
 */
class PcSyncFileManager(
    private val context: Context,
    private val client: PcSyncClient,
    private val localScanner: LocalLibraryScanner = LocalLibraryScanner(context),
) {
    /** 실패(원격 목록 조회 자체가 안 됨)하면 null. 개별 파일 단위 실패는 [PcSyncResult.failed]로 집계된다. */
    suspend fun sync(treeUri: Uri, onProgress: (PcSyncProgress) -> Unit = {}): PcSyncResult? = withContext(Dispatchers.IO) {
        val remoteFiles = client.listFilesRecursively() ?: return@withContext null
        val localFiles = localScanner.scanRecursively(treeUri)
        val localByKey = localFiles.associateBy { syncDeltaKeyOf(it.relativePath) }

        val (toWrite, toDelete) = computeSyncDelta(remoteFiles, localFiles)

        val total = toWrite.size + toDelete.size
        var completed = 0
        var downloaded = 0
        var updated = 0
        var deleted = 0
        var failed = 0

        for (remote in toWrite) {
            onProgress(PcSyncProgress(completed, total, remote.relativePath))
            val existingLocal = localByKey[syncDeltaKeyOf(remote.relativePath)]
            val success = if (existingLocal != null) {
                writeIntoExisting(existingLocal.documentUri, remote)
            } else {
                writeNewFile(treeUri, remote)
            }
            when {
                !success -> failed++
                existingLocal != null -> updated++
                else -> downloaded++
            }
            completed++
        }

        for (local in toDelete) {
            onProgress(PcSyncProgress(completed, total, local.relativePath))
            if (DocumentFile.fromSingleUri(context, local.documentUri)?.delete() == true) deleted++ else failed++
            completed++
        }

        PcSyncResult(downloaded, updated, deleted, failed)
    }

    /** 기존 로컬 파일의 documentUri를 그대로 유지한 채 내용만 갱신 — `BookEntity`가 그 URI로 읽던
     * 위치를 계속 참조하므로, 지우고 새로 만들면 그 기록이 고아가 된다. */
    private suspend fun writeIntoExisting(uri: Uri, remote: PcRemoteFile): Boolean {
        val output = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull() ?: return false
        return output.use { client.downloadFile(remote.relativePath, it) }
    }

    private suspend fun writeNewFile(treeUri: Uri, remote: PcRemoteFile): Boolean {
        val segments = remote.relativePath.split("/")
        val fileName = segments.last()
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val parent = resolveOrCreateFolder(root, segments.dropLast(1)) ?: return false
        val mime = if (fileName.endsWith(".zip", ignoreCase = true)) "application/zip" else "text/plain"
        val newFile = parent.createFile(mime, fileName) ?: return false
        val output = runCatching { context.contentResolver.openOutputStream(newFile.uri, "wt") }.getOrNull() ?: return false
        return output.use { client.downloadFile(remote.relativePath, it) }
    }

    private fun resolveOrCreateFolder(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current = root
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = if (existing != null && existing.isDirectory) existing else (current.createDirectory(segment) ?: return null)
        }
        return current
    }
}

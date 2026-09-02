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

/**
 * PC 트레이 서버 동기화의 델타 계산 + 실제 로컬(SAF) 반영 — .docs/PC_SYNC_SERVER_PLAN.md §3.
 * 단방향(PC → 폰)이라 로컬은 항상 원격의 거울: 원격에만 있으면 받고, 크기/수정시각이 다르면 다시
 * 받고, 로컬에만 있으면 지운다. 델타 비교 키는 [RelativePath.kt]의 정규화 함수를 그대로 재사용해서
 * VSCode 동기화 때 이미 검증된 대소문자/유니코드/구분자 규칙과 일치시킨다 — 다만 실제 파일 생성/조회에는
 * 원본 대소문자 그대로의 경로를 쓴다(정규화된 키는 비교 전용, 실제 파일명을 바꾸면 안 되므로).
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

        val remoteByKey = remoteFiles.associateBy { keyOf(it.relativePath) }
        val localByKey = localFiles.associateBy { keyOf(it.relativePath) }

        val toWrite = remoteByKey.entries.filter { (key, remote) ->
            val local = localByKey[key]
            local == null || local.sizeBytes != remote.sizeBytes || local.lastModifiedMillis != remote.lastModifiedMillis
        }.map { it.value }
        val toDelete = localByKey.filterKeys { it !in remoteByKey }.values.toList()

        val total = toWrite.size + toDelete.size
        var completed = 0
        var downloaded = 0
        var updated = 0
        var deleted = 0
        var failed = 0

        for (remote in toWrite) {
            onProgress(PcSyncProgress(completed, total, remote.relativePath))
            val existingLocal = localByKey[keyOf(remote.relativePath)]
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

    private fun keyOf(relativePath: String): String = normalizeRelativePath(relativePath.split("/"))
}

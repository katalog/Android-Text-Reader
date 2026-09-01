package com.moonkata.textreader.data.file

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.moonkata.textreader.model.FolderEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/**
 * 폴더 탐색기가 필요로 하는 기능 — SAF 실물(`SafFolderBrowser`)과, 테스트에서 실제 SAF 권한 없이
 * 미리 정해둔 목록을 돌려주는 가짜 구현을 같은 자리에 끼워 넣을 수 있게 인터페이스로 분리한다.
 */
interface FolderBrowser {
    fun rootDisplayName(treeUri: Uri): String
    suspend fun listFolder(folderUri: Uri): List<FolderEntry>
    suspend fun listZipEntries(zipUri: Uri): List<FolderEntry.TextFile>
}

/** SAF 트리를 폴더 단위로 한 단계씩 나열 — 전체 재귀 스캔 대신 현재 위치의 자식만 조회. */
class SafFolderBrowser(private val context: Context) : FolderBrowser {

    override fun rootDisplayName(treeUri: Uri): String = DocumentFile.fromTreeUri(context, treeUri)?.name ?: "루트"

    override suspend fun listFolder(folderUri: Uri): List<FolderEntry> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        val entries = mutableListOf<FolderEntry>()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            when {
                child.isDirectory -> entries += FolderEntry.Folder(name, child.uri)
                name.endsWith(".txt", ignoreCase = true) -> entries += FolderEntry.TextFile(
                    name = name,
                    source = BookSource.PlainTxt(child.uri),
                    sizeBytes = child.length(),
                    lastModified = child.lastModified(),
                )
                name.endsWith(".zip", ignoreCase = true) -> entries += FolderEntry.ZipArchive(
                    name = name,
                    uri = child.uri,
                    sizeBytes = child.length(),
                    lastModified = child.lastModified(),
                )
            }
        }
        entries
    }

    override suspend fun listZipEntries(zipUri: Uri): List<FolderEntry.TextFile> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<FolderEntry.TextFile>()
        try {
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (!entry.isDirectory && entryName.endsWith(".txt", ignoreCase = true)) {
                            entries += FolderEntry.TextFile(
                                name = entryName.substringAfterLast('/'),
                                source = BookSource.ZipEntryTxt(zipUri, entryName),
                                sizeBytes = entry.size.takeIf { it >= 0 } ?: 0L,
                                lastModified = entry.time.takeIf { it >= 0 } ?: 0L,
                            )
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            // 손상되었거나 지원하지 않는 zip — 빈 목록으로 처리
        }
        entries
    }
}

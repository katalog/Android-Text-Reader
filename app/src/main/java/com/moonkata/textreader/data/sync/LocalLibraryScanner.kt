package com.moonkata.textreader.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** 로컬 라이브러리 SAF 트리 안의 동기화 대상 파일 하나 — [relativePath]는 라이브러리 루트 기준 `/` 구분. */
data class LocalLibraryFile(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val documentUri: Uri,
)

/**
 * PC 동기화(.docs/PC_SYNC_SERVER_PLAN.md §3)의 델타 계산용 — 라이브러리 SAF 트리 전체를 재귀적으로
 * 순회해서 `.txt`/`.zip` 파일만 나열한다. `SafFolderBrowser`의 `listFolder`는 폴더뷰가 한 단계씩
 * 보여주기 위한 용도라 재귀하지 않는데, 이건 동기화용으로 전체 트리가 한 번에 필요해서 별도로 둔다.
 */
class LocalLibraryScanner(private val context: Context) {

    fun scanRecursively(treeUri: Uri): List<LocalLibraryFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<LocalLibraryFile>()
        walk(root, "", result)
        return result
    }

    private fun walk(dir: DocumentFile, relativePrefix: String, out: MutableList<LocalLibraryFile>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val childRelativePath = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
            if (child.isDirectory) {
                walk(child, childRelativePath, out)
            } else if (name.endsWith(".txt", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)) {
                out += LocalLibraryFile(
                    relativePath = childRelativePath,
                    sizeBytes = child.length(),
                    lastModifiedMillis = child.lastModified(),
                    documentUri = child.uri,
                )
            }
        }
    }
}

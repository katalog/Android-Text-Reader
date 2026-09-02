package com.moonkata.textreader.ui.library

import android.net.Uri
import com.moonkata.textreader.data.file.FolderBrowser
import com.moonkata.textreader.model.FolderEntry

/**
 * 실제 SAF 권한이나 시스템 폴더 선택창 없이, 미리 정해둔 폴더 → 목록 매핑만 돌려주는 테스트 더블.
 * `FolderEntry.TextFile`이 가리키는 실제 파일(`BookSource`)은 진짜 읽을 수 있는 경로(예: 테스트가
 * 직접 써둔 파일의 file:// URI)여야 리더까지 이어지는 시나리오를 검증할 수 있다.
 */
class FakeFolderBrowser(
    private val entriesByLocation: Map<Uri, List<FolderEntry>>,
    private val zipEntriesByUri: Map<Uri, List<FolderEntry.TextFile>> = emptyMap(),
) : FolderBrowser {
    override fun rootDisplayName(treeUri: Uri): String = "테스트 폴더"

    override suspend fun listFolder(folderUri: Uri): List<FolderEntry> = entriesByLocation[folderUri].orEmpty()

    override suspend fun listZipEntries(zipUri: Uri): List<FolderEntry.TextFile> = zipEntriesByUri[zipUri].orEmpty()
}

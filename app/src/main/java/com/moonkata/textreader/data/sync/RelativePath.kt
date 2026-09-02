package com.moonkata.textreader.data.sync

import android.net.Uri
import android.provider.DocumentsContract
import java.text.Normalizer

/**
 * VSCode 읽기 위치 동기화(.docs/VSCODE_SYNC_PLAN.md §3)의 매칭 키 정규화 규칙.
 * 구분자 통일 → NFC 정규화 → 소문자화 순서 — VSCode 확장도 반드시 같은 순서로 적용해야 매칭이 맞는다.
 */
fun normalizeRelativePath(rawSegments: List<String>): String {
    val joined = rawSegments.joinToString("/")
    return Normalizer.normalize(joined.replace('\\', '/'), Normalizer.Form.NFC).lowercase()
}

/**
 * `LibraryViewModel`이 폴더 브라우징 중(BrowseLocation 스택)에만 relativePath를 계산해서 넘기는데,
 * "이어서 읽기" 다이얼로그나 이미 등록된 책을 다시 로드하는 경로는 그 스택을 안 거쳐서 relativePath가
 * 계속 비어있게 되는 문제가 실사용 중 확인됐다(§열린 질문 6 후속 — "니치한 재방문"이라던 가정과 달리
 * "이어서 읽기"가 오히려 제일 흔한 진입 경로였음). SAF 문서 URI의 documentId 문자열이 보통
 * "primary:폴더/하위폴더/파일.txt" 형태로 계층적이라는 점을 이용해, 저장해둔 트리 루트의 documentId를
 * 접두사로 잘라내는 방식으로 relativePath를 역산하는 폴백 — 문서 제공자가 계층적 ID를 쓴다는 가정에
 * 기대는 휴리스틱이라(로컬 스토리지 제공자는 대체로 성립) 100% 보장은 아니지만, 실패해도 null을 돌려줄
 * 뿐 기존 동작에 영향은 없다.
 */
fun relativePathFromSafDocumentUri(documentUri: Uri, treeUri: Uri): String? {
    return try {
        val documentId = DocumentsContract.getDocumentId(documentUri)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        // "$treeDocumentId/" 접두사(구분자까지 포함)로 비교한다 — 단순 startsWith(treeDocumentId)면
        // 트리가 "primary:Books"일 때 형제 트리 "primary:BooksExtra"의 문서도 접두사가 겹쳐 잘못
        // 매칭된다.
        val prefix = "$treeDocumentId/"
        if (!documentId.startsWith(prefix)) return null
        val relative = documentId.removePrefix(prefix)
        if (relative.isEmpty()) return null
        normalizeRelativePath(relative.split("/"))
    } catch (e: Exception) {
        null
    }
}

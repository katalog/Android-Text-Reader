package com.moonkata.textreader.data.sync

import java.text.Normalizer

/**
 * VSCode 읽기 위치 동기화(.docs/VSCODE_SYNC_PLAN.md §3)의 매칭 키 정규화 규칙.
 * 구분자 통일 → NFC 정규화 → 소문자화 순서 — VSCode 확장도 반드시 같은 순서로 적용해야 매칭이 맞는다.
 */
fun normalizeRelativePath(rawSegments: List<String>): String {
    val joined = rawSegments.joinToString("/")
    return Normalizer.normalize(joined.replace('\\', '/'), Normalizer.Form.NFC).lowercase()
}

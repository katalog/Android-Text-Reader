package com.moonkata.textreader.data.sync

import org.json.JSONObject

/**
 * QR 스캔으로 페어링할 때 QR에 담기는 JSON 페이로드 — `type` 필드로 어떤 동기화 기능용인지 구분한다
 * (.docs/SYNC_MULTIUSER_PLAN.md 스테이지 4). VSCode 읽기 위치 동기화(스테이지 5)와 PC 파일 동기화
 * (스테이지 6) 두 페어링 흐름이 이 파싱 로직과 `ui/qr/QrScannerDialog.kt`를 공유한다.
 */
sealed class QrPairingPayload {
    /** VSCode 읽기 위치 동기화용 — `{"type":"vscode_sync","secret":"..."}` */
    data class VscodeSync(val secret: String) : QrPairingPayload()

    /**
     * PC 파일 동기화용 — `{"type":"pc_sync","host":"192.168.0.12","secret":"...","fingerprint":"AB:CD:..."}`
     * `host`는 포트 없이 IP만(`PcSyncClient`가 고정 포트를 스스로 붙이므로) — 처음엔 "포트까지 포함한
     * 문자열"로 잘못 문서화/구현해서 `PcSyncClient`가 포트를 한 번 더 붙여 "IP:포트:포트"가 되는 실제
     * 버그가 있었다(실사용 중 발견, MalformedURLException). QR을 만드는 `pair.go`도 같이 고침.
     */
    data class PcSync(val host: String, val secret: String, val fingerprint: String) : QrPairingPayload()

    companion object {
        /**
         * 파싱 실패, 필수 필드 누락, 모르는 `type`이면 전부 null — 호출부(`QrScannerDialog`)가
         * "잘못된 QR"로 한데 묶어 처리하고 계속 스캔하거나 수동 입력으로 안내할 수 있게 한다.
         */
        fun parse(raw: String): QrPairingPayload? = runCatching {
            val obj = JSONObject(raw)
            when (obj.getString("type")) {
                "vscode_sync" -> VscodeSync(secret = obj.getString("secret"))
                "pc_sync" -> PcSync(
                    host = obj.getString("host"),
                    secret = obj.getString("secret"),
                    fingerprint = obj.getString("fingerprint"),
                )
                else -> null
            }
        }.getOrNull()
    }
}

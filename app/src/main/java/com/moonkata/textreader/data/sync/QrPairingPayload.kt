package com.moonkata.textreader.data.sync

import org.json.JSONObject

/**
 * The JSON payload carried inside a QR code for pairing via QR scan — the `type` field distinguishes which
 * sync feature it's for (.docs/SYNC_MULTIUSER_PLAN.md stage 4). Two pairing flows, VSCode reading-position
 * sync (stage 5) and PC file sync (stage 6), share this parsing logic and `ui/qr/QrScannerDialog.kt`.
 */
sealed class QrPairingPayload {
    /** For VSCode reading-position sync — `{"type":"vscode_sync","secret":"..."}` */
    data class VscodeSync(val secret: String) : QrPairingPayload()

    /**
     * For PC file sync — `{"type":"pc_sync","host":"192.168.0.12","secret":"...","fingerprint":"AB:CD:..."}`
     * `host` is IP-only, with no port (since `PcSyncClient` appends the fixed port itself) — this was
     * initially documented/implemented incorrectly as "a string that includes the port," which caused
     * `PcSyncClient` to append the port a second time, producing "IP:port:port" — a real bug found in
     * actual use (MalformedURLException). `pair.go`, which generates the QR code, was fixed to match.
     */
    data class PcSync(val host: String, val secret: String, val fingerprint: String) : QrPairingPayload()

    companion object {
        /**
         * Returns null for a parse failure, a missing required field, or an unknown `type` — this lets
         * the caller (`QrScannerDialog`) treat them all uniformly as "invalid QR" and either keep
         * scanning or guide the user to manual entry.
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

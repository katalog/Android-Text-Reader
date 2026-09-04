package com.moonkata.textreader.data.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.OutputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** PC 트레이 서버가 공유 중인 파일 하나 — 상대 경로는 `/` 구분(공유 루트 기준). */
data class PcRemoteFile(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/** 고정 포트 — .docs/PC_SYNC_SERVER_PLAN.md §2, PC 서버(별도 저장소 go-moonkata-reader-sync-server)와
 * 동일 값이어야 함. */
const val PC_SYNC_PORT = 58221

/**
 * PC 트레이 서버(별도 저장소 go-moonkata-reader-sync-server, Go)에 접속 — .docs/PC_SYNC_SERVER_PLAN.md §5.
 * `ReadingPositionSyncClient`와 같은 스타일(`HttpURLConnection` 계열 직접 사용, 새 의존성 없음). 공유
 * 시크릿은 `x-moonkata-secret` 헤더로 보낸다.
 *
 * PC 서버는 자체 서명 인증서로 HTTPS를 쓴다(사설 IP엔 공인 인증서를 못 받으므로) — 그래서
 * [pinnedFingerprint]가 null이면(연결 테스트 단계) 어떤 인증서든 받아들이는 대신 실제로 받은 인증서의
 * 지문을 [lastSeenFingerprint]에 기록해서 호출자가 저장할 수 있게 하고, 지문이 주어지면(평소 동기화
 * 단계) 그 값과 정확히 일치하는 인증서만 받아들인다(TOFU 방식, §5 참고).
 */
class PcSyncClient(
    private val host: String,
    private val secret: String,
    private val pinnedFingerprint: String? = null,
) {
    private val baseUrl: String get() = "https://$host:$PC_SYNC_PORT"

    /** 가장 최근 요청에서 실제로 받은 인증서의 지문 — 연결 테스트 성공 시 이 값을 저장해두고 이후
     * 요청부터는 [pinnedFingerprint]로 넘겨서 검증하게 한다. */
    var lastSeenFingerprint: String? = null
        private set

    /**
     * [testConnection]이 실패했을 때 원인 — 예전엔 Log.w로만 남기고 화면엔 안 보여줘서 "네트워크가
     * 안 닿는 건지/지문이 안 맞는 건지/시크릿이 틀린 건지" 구분할 방법이 없었다(VSCode 동기화 쪽에서
     * 똑같은 문제를 겪고 고친 것과 같은 이유로 추가, .docs/SYNC_MULTIUSER_PLAN.md 참고).
     */
    var lastTestConnectionError: String? = null
        private set

    /** 연결 테스트 — 시크릿이 맞는지 `/list` 호출로 확인(성공하면 시크릿도 맞다는 뜻), 이때 받은 인증서
     * 지문을 [lastSeenFingerprint]에 남긴다. */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        lastTestConnectionError = null
        runCatching {
            val connection = openConnection("$baseUrl/list", requireSecret = true)
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                lastTestConnectionError = "HTTP $code" + (if (!errorBody.isNullOrBlank()) ": $errorBody" else "")
                error("HTTP $code")
            }
            connection.inputStream.use { it.readBytes() }
            recordFingerprint(connection)
        }.onFailure {
            Log.w(TAG, "PC 서버 연결 테스트 실패", it)
            if (lastTestConnectionError == null) lastTestConnectionError = "${it.javaClass.simpleName}: ${it.message}"
        }.isSuccess
    }

    /** 원격 파일 목록 — 실패하면 null. */
    suspend fun listFilesRecursively(): List<PcRemoteFile>? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = openConnection("$baseUrl/list", requireSecret = true)
            val files = connection.inputStream.use { input ->
                val array = JSONArray(input.bufferedReader().readText())
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    PcRemoteFile(
                        relativePath = obj.getString("relativePath"),
                        sizeBytes = obj.getLong("sizeBytes"),
                        lastModifiedMillis = obj.getLong("lastModifiedMillis"),
                    )
                }
            }
            recordFingerprint(connection)
            files
        }.onFailure { Log.w(TAG, "PC 서버 파일 목록 조회 실패", it) }.getOrNull()
    }

    /** [relativePath] 파일 내용을 [outputStream]으로 그대로 흘려 받는다. */
    suspend fun downloadFile(relativePath: String, outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(relativePath, "UTF-8").replace("+", "%20")
            val connection = openConnection("$baseUrl/file?path=$encoded", requireSecret = true)
            connection.inputStream.use { input -> input.copyTo(outputStream) }
            recordFingerprint(connection)
        }.onFailure { Log.w(TAG, "PC 서버 파일 다운로드 실패: $relativePath", it) }.isSuccess
    }

    private fun openConnection(url: String, requireSecret: Boolean): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 15_000
            hostnameVerifier = acceptAllHostnameVerifier
            sslSocketFactory = (if (pinnedFingerprint != null) createPinnedSslContext(pinnedFingerprint) else createLenientSslContext()).socketFactory
            if (requireSecret) setRequestProperty("x-moonkata-secret", secret)
        }

    /** [connection]은 이미 `inputStream`을 한 번 읽어서 TLS 핸드셰이크가 끝난 상태여야 한다 — 그 전엔
     * `getServerCertificates()`가 예외를 던진다. */
    private fun recordFingerprint(connection: HttpsURLConnection) {
        runCatching { connection.serverCertificates.firstOrNull() }.getOrNull()?.let {
            lastSeenFingerprint = sha256Fingerprint(it)
        }
    }

    companion object {
        private const val TAG = "PcSyncClient"

        /** "PC 찾기" 스캔용 — 포트가 열려 있어도 우리 서버가 맞는지 `/ping` 응답으로 확인한다. 아직
         * 아무것도 안 믿는 단계라 인증서는 검증하지 않는다(민감한 정보를 주고받지 않는 탐색 단계). */
        suspend fun isPcSyncServer(host: String): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL("https://$host:$PC_SYNC_PORT/ping").openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 800
                    readTimeout = 800
                    hostnameVerifier = acceptAllHostnameVerifier
                    sslSocketFactory = createLenientSslContext().socketFactory
                }
                val body = connection.inputStream.use { it.bufferedReader().readText() }
                body.contains("moonkata-sync-server")
            }.getOrDefault(false)
        }
    }
}

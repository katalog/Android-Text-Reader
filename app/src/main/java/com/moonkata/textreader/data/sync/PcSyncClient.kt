package com.moonkata.textreader.data.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** PC 트레이 서버가 공유 중인 파일 하나 — 상대 경로는 `/` 구분(공유 루트 기준). */
data class PcRemoteFile(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/** 고정 포트 — .docs/PC_SYNC_SERVER_PLAN.md §2, PC 서버(external_library/sync_server)와 동일 값이어야 함. */
const val PC_SYNC_PORT = 58221

/**
 * PC 트레이 서버(external_library/sync_server, Go)에 접속 — .docs/PC_SYNC_SERVER_PLAN.md §2.
 * `ReadingPositionSyncClient`와 같은 스타일(`HttpURLConnection` 직접 사용, 새 의존성 없음). 공유
 * 시크릿은 `x-moonkata-secret` 헤더로 보낸다.
 */
class PcSyncClient(
    private val host: String,
    private val secret: String,
) {
    private val baseUrl: String get() = "http://$host:$PC_SYNC_PORT"

    /** 연결 테스트 — 시크릿이 맞는지 `/list` 호출로 확인(성공하면 시크릿도 맞다는 뜻). */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            openConnection("$baseUrl/list", requireSecret = true).inputStream.use { it.readBytes() }
        }.onFailure { Log.w(TAG, "PC 서버 연결 테스트 실패", it) }.isSuccess
    }

    /** 원격 파일 목록 — 실패하면 null. */
    suspend fun listFilesRecursively(): List<PcRemoteFile>? = withContext(Dispatchers.IO) {
        runCatching {
            openConnection("$baseUrl/list", requireSecret = true).inputStream.use { input ->
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
        }.onFailure { Log.w(TAG, "PC 서버 파일 목록 조회 실패", it) }.getOrNull()
    }

    /** [relativePath] 파일 내용을 [outputStream]으로 그대로 흘려 받는다. */
    suspend fun downloadFile(relativePath: String, outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(relativePath, "UTF-8").replace("+", "%20")
            openConnection("$baseUrl/file?path=$encoded", requireSecret = true).inputStream.use { input ->
                input.copyTo(outputStream)
            }
        }.onFailure { Log.w(TAG, "PC 서버 파일 다운로드 실패: $relativePath", it) }.isSuccess
    }

    private fun openConnection(url: String, requireSecret: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 15_000
            if (requireSecret) setRequestProperty("x-moonkata-secret", secret)
        }

    companion object {
        private const val TAG = "PcSyncClient"

        /** "PC 찾기" 스캔용 — 포트가 열려 있어도 우리 서버가 맞는지 `/ping` 응답으로 확인한다. */
        suspend fun isPcSyncServer(host: String): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL("http://$host:$PC_SYNC_PORT/ping").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 800
                    readTimeout = 800
                }
                val body = connection.inputStream.use { it.bufferedReader().readText() }
                body.contains("moonkata-sync-server")
            }.getOrDefault(false)
        }
    }
}

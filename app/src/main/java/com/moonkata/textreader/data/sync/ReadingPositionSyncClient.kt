package com.moonkata.textreader.data.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RemoteReadingPosition(val charOffset: Int, val source: String, val encoding: String?)

/**
 * Supabase PostgREST 직접 호출 — .docs/VSCODE_SYNC_PLAN.md §1/§4.
 * 실패(네트워크 끊김, 설정값 오류, 파싱 실패 등)는 전부 조용히 null/무시로 처리한다 — best-effort
 * 기능이라 이 클라이언트의 어떤 예외도 리더 화면의 핵심 흐름(로컬 로딩/저장)을 막으면 안 된다.
 */
class ReadingPositionSyncClient(
    private val baseUrl: String,
    private val publishableKey: String,
    private val sharedSecret: String,
) {
    // baseUrl은 프로젝트 기본 주소(예: https://xxx.supabase.co)만 와야 하는데, 실사용 중 GitHub
    // 시크릿에 "/rest/v1"까지 같이 등록돼 있던 적이 실제로 있었다 — 그 상태로 아래에서 또
    // "/rest/v1/reading_positions"를 붙이면 ".../rest/v1/rest/v1/reading_positions"처럼 경로가
    // 겹쳐서 PostgREST가 "PGRST125: invalid path specified in request url"로 거부한다. trim()으로
    // 공백/개행도 같이 방어(build.gradle.kts에서 한 번 trim하지만 이 클라이언트를 다른 곳에서도 쓸 수
    // 있어 여기서 한 번 더), removeSuffix로 실수로 중복된 "/rest/v1"도 걷어낸다.
    private val restBase: String
        get() = "${baseUrl.trim().trimEnd('/').removeSuffix("/rest/v1")}/rest/v1/reading_positions"

    /**
     * [testConnection]이 실패했을 때 원인을 담아둔다 — 예전엔 어떤 예외든 조용히 false 하나로만
     * 뭉개서, "네트워크가 끊겼나/시크릿이 틀렸나/URL 자체가 비었나"를 구분할 방법이 전혀 없었다(실사용
     * 중 원인 특정이 안 돼서 추가). 설정 화면이 실패 이유를 사용자에게 그대로 보여줄 수 있게 한다.
     */
    var lastTestConnectionError: String? = null
        private set

    suspend fun fetch(relativePath: String): RemoteReadingPosition? = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(relativePath, "UTF-8")
            val url = URL("$restBase?select=char_offset,source,encoding&relative_path=eq.$encoded")
            openConnection(url, "GET").inputStream.use { input ->
                val array = JSONArray(input.bufferedReader().readText())
                if (array.length() == 0) return@runCatching null
                val obj = array.getJSONObject(0)
                RemoteReadingPosition(
                    charOffset = obj.getInt("char_offset"),
                    source = obj.getString("source"),
                    encoding = if (obj.isNull("encoding")) null else obj.getString("encoding"),
                )
            }
        }.onFailure { Log.w(TAG, "위치 조회 실패", it) }.getOrNull()
    }

    suspend fun upsert(relativePath: String, charOffset: Int, encoding: String?) {
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = openConnection(URL(restBase), "POST").apply {
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Prefer", "resolution=merge-duplicates")
                    doOutput = true
                }
                val body = JSONObject().apply {
                    put("relative_path", relativePath)
                    put("char_offset", charOffset)
                    put("source", "android")
                    put("encoding", encoding ?: JSONObject.NULL)
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                connection.inputStream.use { it.readBytes() } // 응답을 소비해야 요청이 실제로 완료됨
            }.onFailure { Log.w(TAG, "위치 upsert 실패", it) }
        }
    }

    /**
     * 설정 화면의 "연결 테스트" 버튼용 — 고정된 더미 경로로 upsert를 시도해 시크릿이 RLS를 통과하는지
     * 확인한다. 단순 조회로는 검증이 안 된다 — RLS가 막은 SELECT는 에러가 아니라 그냥 빈 배열을
     * 돌려주므로(§1 curl 검증 때 확인한 내용) "행이 없어서 비었나 시크릿이 틀려서 비었나"를 구분할 수
     * 없다. upsert(INSERT)는 RLS를 어기면 PostgREST가 401/403으로 명확히 거부하므로 이 차이를 이용한다.
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        lastTestConnectionError = if (baseUrl.isBlank() || publishableKey.isBlank()) {
            "SUPABASE_URL/PUBLISHABLE_KEY가 앱 빌드에 비어있음(BuildConfig 주입 안 됨)"
        } else {
            null
        }
        if (lastTestConnectionError != null) return@withContext false

        runCatching {
            val connection = openConnection(URL(restBase), "POST").apply {
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "resolution=merge-duplicates")
                doOutput = true
            }
            val body = JSONObject().apply {
                put("relative_path", CONNECTION_TEST_PATH)
                put("char_offset", 0)
                put("source", "android")
                put("encoding", JSONObject.NULL)
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                lastTestConnectionError = "HTTP $code" + (if (!errorBody.isNullOrBlank()) ": $errorBody" else "")
            }
            code in 200..299
        }.onFailure {
            Log.w(TAG, "연결 테스트 실패", it)
            lastTestConnectionError = "${it.javaClass.simpleName}: ${it.message}"
        }.getOrDefault(false)
    }

    private fun openConnection(url: URL, method: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            // 신규 Supabase 키 체계(publishable/secret)는 apikey 헤더에만 넣는다 — Authorization: Bearer에
            // 같이 넣으면 JWT로 파싱을 시도하다 거부된다(§1 참고).
            setRequestProperty("apikey", publishableKey)
            setRequestProperty("x-moonkata-secret", sharedSecret)
        }

    companion object {
        private const val TAG = "ReadingPositionSync"
        private const val CONNECTION_TEST_PATH = "__connection_test__"
    }
}

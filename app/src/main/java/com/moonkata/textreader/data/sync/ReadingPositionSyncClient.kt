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
 * Calls Supabase PostgREST directly — .docs/VSCODE_SYNC_PLAN.md §1/§4.
 * Every failure (network drop, config error, parse failure, etc.) is silently treated as null/ignored —
 * this is a best-effort feature, so no exception from this client should ever be allowed to block the
 * reader screen's core flow (local loading/saving).
 */
class ReadingPositionSyncClient(
    private val baseUrl: String,
    private val publishableKey: String,
    private val sharedSecret: String,
) {
    // baseUrl should only ever be the project's base address (e.g. https://xxx.supabase.co), but in
    // actual use it happened that a GitHub secret had "/rest/v1" registered as part of it too — if
    // "/rest/v1/reading_positions" is then appended below on top of that, the path overlaps into
    // ".../rest/v1/rest/v1/reading_positions", which PostgREST rejects with "PGRST125: invalid path
    // specified in request url". trim() also guards against stray whitespace/newlines (build.gradle.kts
    // already trims once, but this client can be used elsewhere too, so trim again here), and
    // removeSuffix strips off an accidentally duplicated "/rest/v1" as well.
    private val restBase: String
        get() = "${baseUrl.trim().trimEnd('/').removeSuffix("/rest/v1")}/rest/v1/reading_positions"

    /**
     * Holds the cause when [testConnection] fails — this used to collapse every exception silently into a
     * single false, leaving no way at all to tell whether "the network dropped / the secret is wrong / the
     * URL itself is empty" (added after the root cause couldn't be pinned down during actual use). Lets the
     * settings screen show the failure reason to the user directly.
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
        }.onFailure { Log.w(TAG, "Failed to fetch reading position", it) }.getOrNull()
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
                connection.inputStream.use { it.readBytes() } // the response must be consumed for the request to actually complete
            }.onFailure { Log.w(TAG, "Failed to upsert reading position", it) }
        }
    }

    /**
     * For the "test connection" button on the settings screen — attempts an upsert against a fixed dummy
     * path to confirm the secret passes RLS. A plain read can't verify this — a SELECT blocked by RLS
     * isn't an error, it just returns an empty array (confirmed during the §1 curl verification), so there's
     * no way to distinguish "empty because there are no rows" from "empty because the secret is wrong."
     * An upsert (INSERT) that violates RLS gets a clear 401/403 rejection from PostgREST, so that
     * difference is used here.
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        lastTestConnectionError = if (baseUrl.isBlank() || publishableKey.isBlank()) {
            "SUPABASE_URL/PUBLISHABLE_KEY is empty in the app build (BuildConfig not injected)"
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
            Log.w(TAG, "Connection test failed", it)
            lastTestConnectionError = "${it.javaClass.simpleName}: ${it.message}"
        }.getOrDefault(false)
    }

    private fun openConnection(url: URL, method: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            // The new Supabase key scheme (publishable/secret) only goes in the apikey header — putting it
            // in Authorization: Bearer as well causes it to be rejected as an attempted JWT parse (see §1).
            setRequestProperty("apikey", publishableKey)
            setRequestProperty("x-moonkata-secret", sharedSecret)
        }

    companion object {
        private const val TAG = "ReadingPositionSync"
        private const val CONNECTION_TEST_PATH = "__connection_test__"
    }
}

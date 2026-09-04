package com.moonkata.textreader.data.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.OutputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** A single file being shared by the PC tray server — the relative path uses `/` as separator (rooted at the shared folder). */
data class PcRemoteFile(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/** Fixed port — .docs/PC_SYNC_SERVER_PLAN.md §2; must match the PC server's value (separate repo
 * go-moonkata-reader-sync-server). */
const val PC_SYNC_PORT = 58221

/**
 * Connects to the PC tray server (separate repo go-moonkata-reader-sync-server, Go) —
 * .docs/PC_SYNC_SERVER_PLAN.md §5. Same style as `ReadingPositionSyncClient` (uses `HttpURLConnection`
 * directly, no new dependency). The shared secret is sent via the `x-moonkata-secret` header.
 *
 * The PC server uses HTTPS with a self-signed certificate (a private IP can't get a publicly-trusted
 * certificate) — so when [pinnedFingerprint] is null (during the connection-test stage), any certificate
 * is accepted, but the fingerprint of the certificate actually received is recorded in
 * [lastSeenFingerprint] so the caller can persist it; once a fingerprint is supplied (during normal sync),
 * only a certificate matching it exactly is accepted (TOFU approach, see §5).
 */
class PcSyncClient(
    private val host: String,
    private val secret: String,
    private val pinnedFingerprint: String? = null,
) {
    private val baseUrl: String get() = "https://$host:$PC_SYNC_PORT"

    /** Fingerprint of the certificate actually received in the most recent request — once a connection
     * test succeeds, this value is saved and passed in as [pinnedFingerprint] for subsequent requests to
     * verify against. */
    var lastSeenFingerprint: String? = null
        private set

    /**
     * The cause when [testConnection] fails — this used to only go to Log.w and never surface on screen,
     * so there was no way to tell whether "the network is unreachable / the fingerprint doesn't match /
     * the secret is wrong." Added for the same reason the VSCode sync side hit and fixed the identical
     * problem — see .docs/SYNC_MULTIUSER_PLAN.md.
     */
    var lastTestConnectionError: String? = null
        private set

    /** Connection test — confirms the secret is correct by calling `/list` (success implies the secret is
     * also correct), and records the certificate fingerprint received during that call into
     * [lastSeenFingerprint]. */
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
            Log.w(TAG, "PC server connection test failed", it)
            if (lastTestConnectionError == null) lastTestConnectionError = "${it.javaClass.simpleName}: ${it.message}"
        }.isSuccess
    }

    /** Remote file listing — null on failure. */
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
        }.onFailure { Log.w(TAG, "Failed to fetch PC server file listing", it) }.getOrNull()
    }

    /** Streams the contents of the file at [relativePath] directly into [outputStream]. */
    suspend fun downloadFile(relativePath: String, outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(relativePath, "UTF-8").replace("+", "%20")
            val connection = openConnection("$baseUrl/file?path=$encoded", requireSecret = true)
            connection.inputStream.use { input -> input.copyTo(outputStream) }
            recordFingerprint(connection)
        }.onFailure { Log.w(TAG, "Failed to download file from PC server: $relativePath", it) }.isSuccess
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

    /** [connection] must have already had its `inputStream` read once so the TLS handshake has completed
     * — before that, `getServerCertificates()` throws. */
    private fun recordFingerprint(connection: HttpsURLConnection) {
        runCatching { connection.serverCertificates.firstOrNull() }.getOrNull()?.let {
            lastSeenFingerprint = sha256Fingerprint(it)
        }
    }

    companion object {
        private const val TAG = "PcSyncClient"

        /** For the "find PC" scan — even if the port is open, confirms it's actually our server via the
         * `/ping` response. Certificates aren't validated at this stage since nothing is trusted yet
         * (a discovery step that doesn't exchange sensitive information). */
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

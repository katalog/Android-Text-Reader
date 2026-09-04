package com.moonkata.textreader.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Verifies the real request/response contract that `PcSyncClient` exchanges with the PC tray server
 * (HTTPS, self-signed certificate + TOFU fingerprint pinning) against a local MockWebServer (HTTPS) —
 * .docs/PC_SYNC_SERVER_PLAN.md §5.
 *
 * `PcSyncClient`'s port is [PC_SYNC_PORT] (a fixed value) that callers cannot change, so MockWebServer
 * is also started directly on that fixed port rather than a random one — no other process on this
 * device (the Android device running the tests) actually uses that port locally (the PC-side server
 * is a port on a separate device, so there's no conflict).
 */
@RunWith(AndroidJUnit4::class)
class PcSyncClientTest {

    private lateinit var server: MockWebServer
    private lateinit var serverFingerprint: String

    @Before
    fun startServer() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val handshakeCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        serverFingerprint = sha256Fingerprint(certificate.certificate)

        server = MockWebServer()
        server.useHttps(handshakeCertificates.sslSocketFactory(), false)
        server.start(PC_SYNC_PORT)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testConnection_lenientMode_succeedsAndRecordsTheServerCertificateFingerprint() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val client = PcSyncClient("127.0.0.1", "secret")

        val success = runBlocking { client.testConnection() }

        assertTrue(success)
        assertEquals(serverFingerprint, client.lastSeenFingerprint)
    }

    @Test
    fun testConnection_sendsTheSharedSecretHeader() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val client = PcSyncClient("127.0.0.1", "my-secret")

        runBlocking { client.testConnection() }

        assertEquals("my-secret", server.takeRequest().getHeader("x-moonkata-secret"))
    }

    @Test
    fun pinnedMode_withTheCorrectFingerprint_succeeds() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val client = PcSyncClient("127.0.0.1", "secret", pinnedFingerprint = serverFingerprint)

        assertTrue(runBlocking { client.testConnection() })
    }

    @Test
    fun pinnedMode_withAWrongFingerprint_isRejected() {
        // The core defense of TOFU — if the stored fingerprint differs from the actually received
        // certificate fingerprint (switched to a different PC, or a man-in-the-middle attack), the
        // connection must be rejected even if the secret is correct.
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val client = PcSyncClient("127.0.0.1", "secret", pinnedFingerprint = "00:11:22:33:AA:BB")

        assertFalse(runBlocking { client.testConnection() })
    }

    @Test
    fun listFilesRecursively_parsesTheJsonArray() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"relativePath":"a.txt","sizeBytes":10,"lastModifiedMillis":1000},""" +
                    """{"relativePath":"folder/b.zip","sizeBytes":20,"lastModifiedMillis":2000}]"""
            )
        )
        val client = PcSyncClient("127.0.0.1", "secret")

        val files = runBlocking { client.listFilesRecursively() }

        assertEquals(
            listOf(
                PcRemoteFile("a.txt", 10, 1000),
                PcRemoteFile("folder/b.zip", 20, 2000),
            ),
            files,
        )
    }

    @Test
    fun listFilesRecursively_returnsNullOnFailure() {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = PcSyncClient("127.0.0.1", "secret")

        assertNull(runBlocking { client.listFilesRecursively() })
    }

    @Test
    fun downloadFile_streamsTheExactResponseBytes() {
        val fileBytes = ByteArray(4096) { it.toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(fileBytes)))
        val client = PcSyncClient("127.0.0.1", "secret")
        val output = ByteArrayOutputStream()

        val success = runBlocking { client.downloadFile("folder/book.txt", output) }

        assertTrue(success)
        assertArrayEquals(fileBytes, output.toByteArray())
    }

    @Test
    fun downloadFile_urlEncodesTheRequestedPath() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x"))
        val client = PcSyncClient("127.0.0.1", "secret")

        runBlocking { client.downloadFile("폴더/책 1.txt", ByteArrayOutputStream()) }

        val path = server.takeRequest().path.orEmpty()
        assertTrue("The path should start with /file?path=: $path", path.startsWith("/file?path="))
        assertFalse("The encoded path should not contain a raw space: $path", path.contains(" "))
    }

    @Test
    fun isPcSyncServer_true_whenPingBodyIdentifiesOurServer() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"app":"moonkata-sync-server","version":"0.1.0"}""")
        )

        assertTrue(runBlocking { PcSyncClient.isPcSyncServer("127.0.0.1") })
    }

    @Test
    fun isPcSyncServer_false_whenTheResponseIsSomeOtherService() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"app":"some-other-service"}"""))

        assertFalse(runBlocking { PcSyncClient.isPcSyncServer("127.0.0.1") })
    }
}

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
 * `PcSyncClient`가 PC 트레이 서버(HTTPS, 자체 서명 인증서 + TOFU 지문 고정)와 주고받는 실제 요청/응답
 * 계약을 로컬 MockWebServer(HTTPS)로 검증한다 — .docs/PC_SYNC_SERVER_PLAN.md §5.
 *
 * `PcSyncClient`의 포트는 [PC_SYNC_PORT](고정값)라 호출자가 바꿀 수 없으므로, MockWebServer도 임의
 * 포트가 아니라 그 고정 포트로 직접 띄운다 — 이 기기(테스트를 실행하는 안드로이드) 로컬에서 그 포트를
 * 실제로 쓰는 다른 프로세스는 없다(PC 쪽 서버는 별도 기기의 포트라 충돌하지 않음).
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
        // TOFU의 핵심 방어선 — 저장해둔 지문과 실제로 받은 인증서 지문이 다르면(다른 PC로 바뀌었거나
        // 중간자 공격) 시크릿이 맞아도 거부해야 한다.
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
        assertTrue("경로가 /file?path=로 시작해야 함: $path", path.startsWith("/file?path="))
        assertFalse("인코딩된 경로엔 원시 공백이 없어야 함: $path", path.contains(" "))
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

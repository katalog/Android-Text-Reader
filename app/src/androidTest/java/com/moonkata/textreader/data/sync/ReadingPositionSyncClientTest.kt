package com.moonkata.textreader.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ReadingPositionSyncClient`가 Supabase PostgREST에 보내는 실제 요청 모양(경로/헤더/바디)과 응답
 * 파싱을 로컬 가짜 서버(MockWebServer)로 검증한다 — 진짜 Supabase 프로젝트 없이 프로토콜 계약만 확인.
 */
@RunWith(AndroidJUnit4::class)
class ReadingPositionSyncClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ReadingPositionSyncClient

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        client = ReadingPositionSyncClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            publishableKey = "test-publishable-key",
            sharedSecret = "test-shared-secret",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetch_parsesTheFirstRowOfANonEmptyArray() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"char_offset":1234,"source":"vscode","encoding":"UTF-8"}]"""
            )
        )

        val result = runBlocking { client.fetch("folder/book.txt") }

        assertEquals(1234, result?.charOffset)
        assertEquals("vscode", result?.source)
        assertEquals("UTF-8", result?.encoding)
    }

    @Test
    fun fetch_treatsNullJsonEncodingAsNullEncoding() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"char_offset":0,"source":"android","encoding":null}]"""
            )
        )

        val result = runBlocking { client.fetch("book.txt") }

        assertNull(result?.encoding)
    }

    @Test
    fun fetch_returnsNullWhenNoRowMatches() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val result = runBlocking { client.fetch("book.txt") }

        assertNull(result)
    }

    @Test
    fun fetch_returnsNullOnServerError_insteadOfThrowing() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = runBlocking { client.fetch("book.txt") }

        assertNull(result)
    }

    @Test
    fun fetch_sendsApikeyAndSecretHeaders_andUrlEncodesTheRelativePath() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        runBlocking { client.fetch("폴더/책 1.txt") }

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("test-publishable-key", request.getHeader("apikey"))
        assertEquals("test-shared-secret", request.getHeader("x-moonkata-secret"))
        assertTrue(
            "인코딩된 상대경로가 쿼리에 포함돼야 함: ${request.path}",
            request.path?.contains("relative_path=eq.") == true && request.path?.contains(" ") != true,
        )
    }

    @Test
    fun upsert_sendsRelativePathOffsetSourceAndEncodingAsJson_withMergeDuplicatesPreferHeader() {
        server.enqueue(MockResponse().setResponseCode(201))

        runBlocking { client.upsert("folder/book.txt", 4321, "EUC-KR") }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("resolution=merge-duplicates", request.getHeader("Prefer"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("folder/book.txt", body.getString("relative_path"))
        assertEquals(4321, body.getInt("char_offset"))
        assertEquals("android", body.getString("source"))
        assertEquals("EUC-KR", body.getString("encoding"))
    }

    @Test
    fun upsert_sendsJsonNull_whenEncodingIsUnknown() {
        server.enqueue(MockResponse().setResponseCode(201))

        runBlocking { client.upsert("book.txt", 0, null) }

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.isNull("encoding"))
    }

    @Test
    fun testConnection_returnsTrueForA2xxResponse() {
        server.enqueue(MockResponse().setResponseCode(201))

        assertTrue(runBlocking { client.testConnection() })
    }

    @Test
    fun testConnection_returnsFalseWhenTheSecretIsRejected() {
        server.enqueue(MockResponse().setResponseCode(401))

        assertFalse(runBlocking { client.testConnection() })
    }
}

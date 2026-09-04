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
 * Verifies the actual shape of the requests `ReadingPositionSyncClient` sends to Supabase
 * PostgREST (path/headers/body) and its response parsing, using a local fake server
 * (MockWebServer) — checks only the protocol contract, without a real Supabase project.
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
            "The encoded relative path must be included in the query: ${request.path}",
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

    /**
     * In real usage, the GitHub secret SUPABASE_URL was once registered incorrectly as
     * "https://xxx.supabase.co/rest/v1" (including the trailing /rest/v1) — the client then
     * appended "/rest/v1/reading_positions" again, duplicating the path
     * (".../rest/v1/rest/v1/reading_positions"), which Supabase rejected with
     * "PGRST125: invalid path". Even if whitespace/newlines get mixed into the end of baseUrl, or
     * "/rest/v1" is duplicated, the actual request path must always be exactly one
     * "/rest/v1/reading_positions".
     */
    @Test
    fun restBase_stripsDuplicateRestV1SuffixAndWhitespace_fromBaseUrl() {
        val messyClient = ReadingPositionSyncClient(
            baseUrl = server.url("/").toString().trimEnd('/') + "/rest/v1 \n",
            publishableKey = "test-publishable-key",
            sharedSecret = "test-shared-secret",
        )
        server.enqueue(MockResponse().setResponseCode(201))

        assertTrue(runBlocking { messyClient.testConnection() })

        val request = server.takeRequest()
        assertTrue(
            "The path must be exactly /rest/v1/reading_positions once (no duplication/whitespace): ${request.path}",
            request.path?.startsWith("/rest/v1/reading_positions") == true,
        )
        assertFalse("'/rest/v1' must not remain duplicated: ${request.path}", request.path?.contains("v1/rest") == true)
    }
}

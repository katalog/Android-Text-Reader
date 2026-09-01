package com.moonkata.textreader.data.font

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 인터넷(GitHub) 대신 로컬 가짜 HTTP 서버(MockWebServer)로 다운로드 성공/실패 경로를 검증한다.
 * cleartext(http://) 요청이 필요해 debug 소스셋의 network_security_config.xml에서 localhost/127.0.0.1만
 * 예외로 허용해뒀다(릴리스 빌드에는 영향 없음).
 */
@RunWith(AndroidJUnit4::class)
class FontDownloadManagerTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val downloadManager = FontDownloadManager(application)
    private lateinit var server: MockWebServer

    private fun testEntry(path: String) = FontCatalogEntry(
        id = "test_font",
        displayName = "테스트 폰트",
        license = "test",
        downloadUrl = server.url(path).toString(),
        localFileName = "test_font.ttf",
    )

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        downloadManager.delete(testEntry("/cleanup"))
    }

    @Test
    fun successfulDownload_savesFileAndEndsInDownloadedState() {
        val fontBytes = ByteArray(64 * 1024) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(fontBytes)).setResponseCode(200))
        val entry = testEntry("/font.ttf")

        val states = runBlocking { downloadManager.download(entry).toList() }

        assertTrue("마지막 상태가 Downloaded여야 함", states.last() is FontDownloadState.Downloaded)
        assertTrue("Downloading 진행률 상태가 최소 한 번은 있어야 함", states.any { it is FontDownloadState.Downloading })

        val savedFile = downloadManager.localFile(entry)
        assertTrue("다운로드한 파일이 실제로 저장돼야 함", savedFile.exists())
        assertArrayEquals("저장된 내용이 서버가 보낸 바이트와 정확히 같아야 함", fontBytes, savedFile.readBytes())
        assertTrue(downloadManager.isDownloaded(entry))
    }

    @Test
    fun failedDownload_endsInFailedState_andLeavesNoFinalFile() {
        server.enqueue(MockResponse().setResponseCode(404))
        val entry = testEntry("/missing.ttf")

        val states = runBlocking { downloadManager.download(entry).toList() }

        assertTrue("실패하면 Failed 상태로 끝나야 함", states.last() is FontDownloadState.Failed)
        assertFalse("실패한 다운로드는 최종 파일을 남기면 안 됨", downloadManager.localFile(entry).exists())
    }
}

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
 * Verifies the download success/failure paths against a local fake HTTP server (MockWebServer)
 * instead of the real internet (GitHub). This requires cleartext (http://) requests, so the debug
 * source set's network_security_config.xml allows an exception only for localhost/127.0.0.1 (no
 * effect on release builds).
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

        assertTrue("The final state should be Downloaded", states.last() is FontDownloadState.Downloaded)
        assertTrue("There should be at least one Downloading progress state", states.any { it is FontDownloadState.Downloading })

        val savedFile = downloadManager.localFile(entry)
        assertTrue("The downloaded file should actually be saved", savedFile.exists())
        assertArrayEquals("The saved content should exactly match the bytes the server sent", fontBytes, savedFile.readBytes())
        assertTrue(downloadManager.isDownloaded(entry))
    }

    @Test
    fun failedDownload_endsInFailedState_andLeavesNoFinalFile() {
        server.enqueue(MockResponse().setResponseCode(404))
        val entry = testEntry("/missing.ttf")

        val states = runBlocking { downloadManager.download(entry).toList() }

        assertTrue("On failure it should end in the Failed state", states.last() is FontDownloadState.Failed)
        assertFalse("A failed download must not leave behind a final file", downloadManager.localFile(entry).exists())
    }
}

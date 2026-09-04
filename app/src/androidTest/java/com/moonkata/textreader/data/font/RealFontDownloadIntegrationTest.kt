package com.moonkata.textreader.data.font

import android.app.Application
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `FontDownloadManagerTest` (MockWebServer) and `FontResolverTest` (dummy files) verify the
 * logic/contract with fakes, but "does it actually download and apply from GitHub" only means
 * something if checked against the real internet at least once — `FontCatalog`'s download URLs
 * (GitHub, etc.) are documented as capable of silently breaking if the host changes, and this
 * test is exactly the "is the link still alive" check (it actually caught nanum_gothic/
 * nanum_myeongjo/ridibatang being broken, which led to swapping their sources). It covers every
 * entry in `FontCatalog` one by one — it must run on a real device against the real network, and
 * unlike the rest of the suite (like `FontDownloadManagerTest`) it can fail depending on
 * connectivity/host conditions (that's the point of this test — it's closer to a manual check
 * than a routine regression suite).
 */
@RunWith(AndroidJUnit4::class)
class RealFontDownloadIntegrationTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val downloadManager = FontDownloadManager(application)

    // TrueType (.ttf) always starts with 0x00010000, and OpenType/CFF (.otf) always starts with
    // 'OTTO' — used to distinguish a real font from something like an HTML error page saved by
    // the host instead.
    private val ttfMagicNumber = byteArrayOf(0x00, 0x01, 0x00, 0x00)
    private val otfMagicNumber = "OTTO".toByteArray(Charsets.US_ASCII)

    @After
    fun cleanup() {
        FontCatalog.entries.forEach { downloadManager.delete(it) }
    }

    @Test
    fun nanumGothic_reallyDownloadsAndAppliesAsADistinctFont() = runBlocking {
        verifyRealDownloadAndApply("nanum_gothic")
    }

    @Test
    fun nanumMyeongjo_reallyDownloadsAndAppliesAsADistinctFont() = runBlocking {
        verifyRealDownloadAndApply("nanum_myeongjo")
    }

    @Test
    fun notoSansKr_reallyDownloadsAndAppliesAsADistinctFont() = runBlocking {
        verifyRealDownloadAndApply("noto_sans_kr")
    }

    @Test
    fun ridibatang_reallyDownloadsAndAppliesAsADistinctFont() = runBlocking {
        verifyRealDownloadAndApply("ridibatang")
    }

    @Test
    fun pretendard_reallyDownloadsAndAppliesAsADistinctFont() = runBlocking {
        verifyRealDownloadAndApply("pretendard")
    }

    private suspend fun verifyRealDownloadAndApply(fontId: String) {
        val entry = FontCatalog.findById(fontId)!!
        downloadManager.delete(entry) // Clear out any file left by a previous run and download fresh.

        val states = downloadManager.download(entry).toList()

        assertTrue(
            "The real download should end in Downloaded — the URL (${entry.downloadUrl}) may be " +
                "broken, or there may be no network. Final state: ${states.lastOrNull()}",
            states.last() is FontDownloadState.Downloaded,
        )

        val file = downloadManager.localFile(entry)
        assertTrue("The downloaded file should actually be saved", file.exists())
        assertTrue(
            "Too small for a font file (${file.length()} bytes) — it may be corrupted or an error page may have been saved",
            file.length() > 100_000,
        )

        val expectedMagicNumber = if (entry.localFileName.endsWith(".otf")) otfMagicNumber else ttfMagicNumber
        val header = ByteArray(expectedMagicNumber.size)
        file.inputStream().use { it.read(header) }
        assertArrayEquals("Not a font file magic number — this may not be a genuine font file", expectedMagicNumber, header)

        val resolved = FontResolver.resolve(application, entry.id)
        assertNotSame("Applying it should yield the actually downloaded font, not the system default", FontFamily.Default, resolved)
    }
}

package com.moonkata.textreader.data.font

import android.app.Application
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The core contract of "does selecting a font actually apply a different font": FontResolver
 * decides between a custom FontFamily and FontFamily.Default solely by whether the font file
 * exists locally — so faking just the file's presence, without a real download, is enough to
 * verify this contract precisely (the file content doesn't need to be a valid font, since
 * FontResolver never inspects the content).
 */
@RunWith(AndroidJUnit4::class)
class FontResolverTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val downloadManager = FontDownloadManager(application)
    private val entry = FontCatalog.entries.first()

    @After
    fun cleanup() {
        downloadManager.delete(entry)
    }

    @Test
    fun systemDefaultId_alwaysResolvesToFontFamilyDefault() {
        val resolved = FontResolver.resolve(application, FontCatalog.SYSTEM_DEFAULT_ID)

        assertSame(FontFamily.Default, resolved)
    }

    @Test
    fun unknownFontId_fallsBackToFontFamilyDefault() {
        val resolved = FontResolver.resolve(application, "존재하지 않는 폰트 id")

        assertSame(FontFamily.Default, resolved)
    }

    @Test
    fun catalogEntryNotYetDownloaded_fallsBackToFontFamilyDefault() {
        downloadManager.delete(entry) // Make sure any file left by a previous test is removed

        val resolved = FontResolver.resolve(application, entry.id)

        assertSame(FontFamily.Default, resolved)
    }

    @Test
    fun catalogEntryDownloaded_resolvesToACustomFontFamily_notDefault() {
        downloadManager.localFile(entry).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val resolved = FontResolver.resolve(application, entry.id)

        assertNotSame("When a local file exists it should be a custom font, not the system default", FontFamily.Default, resolved)
    }
}

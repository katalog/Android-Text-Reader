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
 * "폰트를 선택하면 실제로 다른 폰트가 적용되는지"의 핵심 계약: FontResolver는 로컬에 폰트 파일이
 * 있는지만 보고 커스텀 FontFamily/FontFamily.Default를 가른다 — 실제 다운로드 없이도 파일 유무만
 * 흉내내면 이 계약을 정확히 검증할 수 있다(파일 내용까지 유효한 폰트일 필요는 없음, FontResolver는
 * 내용을 검사하지 않으므로).
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
        downloadManager.delete(entry) // 이전 테스트가 남긴 파일이 있으면 확실히 지운다

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

        assertNotSame("로컬 파일이 있으면 시스템 기본 폰트가 아닌 커스텀 폰트여야 함", FontFamily.Default, resolved)
    }
}

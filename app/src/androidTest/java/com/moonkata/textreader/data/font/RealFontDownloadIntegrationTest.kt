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
 * `FontDownloadManagerTest`(MockWebServer)/`FontResolverTest`(더미 파일)는 로직/계약을 가짜로
 * 검증하지만, "실제로 GitHub에서 다운로드가 되고 적용까지 되는지"는 진짜 인터넷으로 한 번은 확인해야
 * 의미가 있다 — `FontCatalog`의 다운로드 URL(GitHub 등)은 배포처가 바뀌면 조용히 깨질 수 있다고
 * 문서화돼 있는데, 이 테스트가 바로 그 "링크가 살아있는지" 확인 역할을 한다(실제로 nanum_gothic/
 * nanum_myeongjo/ridibatang 세 개가 이 테스트로 깨져있는 게 잡혀서 소스를 교체했다). `FontCatalog`의
 * 항목 전부를 하나씩 커버한다 — 실기기에서 실제 네트워크로 돌아야 하고, `FontDownloadManagerTest` 같은
 * 나머지 스위트와 달리 오프라인/배포처 사정에 따라 실패할 수 있다(그게 이 테스트의 목적이기도 함 —
 * 정기 회귀 스위트라기보단 수동 확인용에 가깝다).
 */
@RunWith(AndroidJUnit4::class)
class RealFontDownloadIntegrationTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val downloadManager = FontDownloadManager(application)

    // TrueType(.ttf)은 항상 0x00010000으로, OpenType/CFF(.otf)는 항상 'OTTO'로 시작한다 — 진짜
    // 폰트가 아니라 배포처의 HTML 에러 페이지 같은 게 저장된 게 아닌지 구분하는 용도.
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
        downloadManager.delete(entry) // 이전 실행이 남긴 파일이 있으면 지우고 처음부터 다시 받는다.

        val states = downloadManager.download(entry).toList()

        assertTrue(
            "실제 다운로드가 Downloaded로 끝나야 함 — URL(${entry.downloadUrl})이 깨졌거나 " +
                "네트워크가 없을 수 있음. 실패 상태: ${states.lastOrNull()}",
            states.last() is FontDownloadState.Downloaded,
        )

        val file = downloadManager.localFile(entry)
        assertTrue("다운로드한 파일이 실제로 저장돼야 함", file.exists())
        assertTrue(
            "폰트 파일치고는 너무 작음(${file.length()} bytes) — 손상됐거나 에러 페이지가 저장됐을 수 있음",
            file.length() > 100_000,
        )

        val expectedMagicNumber = if (entry.localFileName.endsWith(".otf")) otfMagicNumber else ttfMagicNumber
        val header = ByteArray(expectedMagicNumber.size)
        file.inputStream().use { it.read(header) }
        assertArrayEquals("폰트 파일 매직 넘버가 아님 — 진짜 폰트 파일이 아닐 수 있음", expectedMagicNumber, header)

        val resolved = FontResolver.resolve(application, entry.id)
        assertNotSame("적용하면 시스템 기본 폰트가 아닌 실제 다운로드한 폰트여야 함", FontFamily.Default, resolved)
    }
}

package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.font.FontCatalog
import com.moonkata.textreader.data.font.FontDownloadManager
import com.moonkata.textreader.data.font.FontDownloadState
import com.moonkata.textreader.data.font.FontResolver
import com.moonkata.textreader.data.parser.PaginationParams
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.TestTextMeasurer
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "폰트를 다운로드해서 적용하면 뷰어에 실제로 다르게 보이는지"의 end-to-end 검증. 실제 소설 +
 * 실제로 다운로드한 폰트로, 뷰어(`ReaderPagerContent`)가 정확히 하는 일을 그대로 재현한다 —
 * `LaunchedEffect`에서 `settings.fontFamilyId`가 바뀔 때마다 `FontResolver.resolve`로 새
 * `FontFamily`를 구해 `PaginationParams`에 담아 `onViewportMeasured`를 호출하는 것. 화면을 실제로
 * 렌더링해 픽셀을 비교하진 않지만(이 앱 종류에서 업계적으로도 잘 안 쓰는 스크린샷 테스트가 필요해
 * 제외), 다른 폰트는 같은 텍스트라도 한 페이지에 들어가는 분량이 달라지므로, 폰트 적용 전후로 페이지
 * 경계(끝 오프셋)가 실제로 달라지는지 확인하면 "뷰어가 진짜 다른 폰트로 다시 계산해 그렸다"는 걸
 * 안정적으로 증명할 수 있다.
 */
@RunWith(AndroidJUnit4::class)
class FontApplyRepaginatesViewerTest {

    @Test
    fun applyingADownloadedFont_changesWhatFitsOnThePage() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val fontDownloadManager = FontDownloadManager(application)
        val fontEntry = FontCatalog.findById("nanum_myeongjo")!!

        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        fontDownloadManager.delete(fontEntry)
        runBlocking {
            settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
            settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
        }
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            waitUntilTrue {
                val settings = viewModel.uiState.value.settings
                settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE && settings.autoAdvanceMode == AutoAdvanceMode.OFF
            }

            val textMeasurer = TestTextMeasurer.create(application)

            // 1) 시스템 기본 폰트로 먼저 페이지를 계산해둔다.
            val defaultFontFamily = FontResolver.resolve(application, FontCatalog.SYSTEM_DEFAULT_ID)
            viewModel.onViewportMeasured(textMeasurer, testParams(defaultFontFamily))
            waitUntilTrue { viewModel.uiState.value.currentPage != null }
            val defaultFontPage = viewModel.uiState.value.currentPage!!

            // 2) 실제로 나눔명조를 다운로드하고 적용한다(진짜 인터넷 사용).
            val states = runBlocking { fontDownloadManager.download(fontEntry).toList() }
            assertTrue(
                "실제 다운로드가 성공해야 이 테스트를 계속할 수 있음. 실패 상태: ${states.lastOrNull()}",
                states.last() is FontDownloadState.Downloaded,
            )
            viewModel.selectFont(fontEntry.id)
            waitUntilTrue { viewModel.uiState.value.settings.fontFamilyId == fontEntry.id }

            // 3) 폰트가 바뀌었으니 뷰어라면 다시 FontResolver로 새 FontFamily를 구해 재계산했을 것 —
            //    그걸 그대로 재현한다.
            val customFontFamily = FontResolver.resolve(application, fontEntry.id)
            viewModel.onViewportMeasured(textMeasurer, testParams(customFontFamily))
            waitUntilTrue { viewModel.uiState.value.currentPage != defaultFontPage }
            val customFontPage = viewModel.uiState.value.currentPage!!

            assertNotEquals(
                "다른 폰트를 적용했으면 같은 시작 지점이라도 페이지에 들어가는 분량이 달라져야 함" +
                    "(뷰어가 실제로 다시 그렸다는 증거)",
                defaultFontPage.endOffset,
                customFontPage.endOffset,
            )
        } finally {
            runBlocking {
                settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
                settingsRepository.updateFontFamilyId(originalSettings.fontFamilyId)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
            fontDownloadManager.delete(fontEntry)
        }
    }

    private fun testParams(fontFamily: FontFamily) = PaginationParams(
        fontFamily = fontFamily,
        fontSizeSp = 18f.sp,
        lineHeightMultiplier = 1.5f,
        letterSpacingSp = 0f.sp,
        contentWidthPx = 1000,
        contentHeightPx = 2000,
        textColor = Color.Black,
    )
}

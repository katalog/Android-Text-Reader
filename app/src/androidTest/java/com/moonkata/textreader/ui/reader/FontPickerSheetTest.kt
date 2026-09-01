package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.font.FontCatalog
import com.moonkata.textreader.data.font.FontDownloadManager
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `FontPickerSheet`의 실제 클릭 흐름: 다운로드 안 된 폰트는 선택 불가(라디오 버튼 비활성)이고,
 * 이미 다운로드된 폰트를 탭하면 실제로 `viewModel.selectFont`가 호출돼 설정에 반영되는지 검증한다.
 * 다운로드 메커니즘 자체(성공/실패/진행률)는 `FontDownloadManagerTest`/`RealFontDownloadIntegrationTest`가
 * 이미 다루므로, 여기서는 "이미 다운로드된 상태"를 더미 파일로 흉내내 클릭 흐름만 빠르게 검증한다
 * (`FontResolverTest`와 같은 패턴 — 파일 내용까지 유효한 폰트일 필요는 없음).
 */
@RunWith(AndroidJUnit4::class)
class FontPickerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingADownloadedFont_selectsItInTheViewModel() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val settingsRepository = ReaderSettingsRepository(application)
        val fontDownloadManager = FontDownloadManager(application)
        val fontEntry = FontCatalog.entries.first()

        val originalFontFamilyId = runBlocking { settingsRepository.settingsFlow.first() }.fontFamilyId
        fontDownloadManager.delete(fontEntry)
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }

            // 실제 다운로드 없이 "이미 다운로드된 상태"만 흉내낸다.
            fontDownloadManager.localFile(fontEntry).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    FontPickerSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("${fontEntry.displayName} (${fontEntry.license})").performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                viewModel.uiState.value.settings.fontFamilyId == fontEntry.id
            }

            assertEquals(fontEntry.id, viewModel.uiState.value.settings.fontFamilyId)
        } finally {
            runBlocking {
                settingsRepository.updateFontFamilyId(originalFontFamilyId)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
            fontDownloadManager.delete(fontEntry)
        }
    }
}

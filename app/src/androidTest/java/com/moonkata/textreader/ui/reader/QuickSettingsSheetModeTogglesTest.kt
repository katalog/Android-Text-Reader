package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.LineBreakMode
import com.moonkata.textreader.data.datastore.OrientationLock
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.db.AppDatabase
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
 * `QuickSettingsSheetTest`는 폰트/여백/테마/전환 애니메이션만 다루는데, 시트에는 그 외에도 실제
 * 사용자 시나리오(USER_SCENARIOS.md §11)로 문서화된 토글이 세 개 더 있다 — 읽기 모드 전환, 줄바꿈
 * 정리 모드, 화면 꺼짐 방지. 지금까지 자동화 테스트가 하나도 없던 경로라 여기서 따로 겨냥한다.
 */
@RunWith(AndroidJUnit4::class)
class QuickSettingsSheetModeTogglesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pageTurnMode_lineBreakMode_andKeepScreenOn_toggleAndPersist() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }

        val targetPageTurnMode = if (originalSettings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE) PageTurnMode.VERTICAL_SCROLL else PageTurnMode.HORIZONTAL_PAGE
        val pageTurnLabel = if (targetPageTurnMode == PageTurnMode.HORIZONTAL_PAGE) "페이지 넘김" else "스크롤"
        val targetLineBreakMode = if (originalSettings.lineBreakMode == LineBreakMode.PRESERVE) LineBreakMode.REFLOW else LineBreakMode.PRESERVE
        val lineBreakLabel = if (targetLineBreakMode == LineBreakMode.PRESERVE) "원문 유지" else "문단 재구성"
        val targetKeepScreenOn = !originalSettings.keepScreenOnEnabled
        val targetOrientationLock = when (originalSettings.orientationLock) {
            OrientationLock.AUTO -> OrientationLock.PORTRAIT
            OrientationLock.PORTRAIT -> OrientationLock.LANDSCAPE
            OrientationLock.LANDSCAPE -> OrientationLock.AUTO
        }
        val orientationLabel = mapOf(
            OrientationLock.AUTO to "자동",
            OrientationLock.PORTRAIT to "세로",
            OrientationLock.LANDSCAPE to "가로",
        ).getValue(targetOrientationLock)

        try {
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    QuickSettingsSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText(pageTurnLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.pageTurnMode == targetPageTurnMode }

            composeTestRule.onNodeWithText(lineBreakLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.lineBreakMode == targetLineBreakMode }

            composeTestRule.onNodeWithText("화면 꺼짐 방지").performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.keepScreenOnEnabled == targetKeepScreenOn }

            composeTestRule.onNodeWithText(orientationLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.orientationLock == targetOrientationLock }

            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertEquals("읽기 모드 전환이 DataStore에 저장돼야 함", targetPageTurnMode, persisted.pageTurnMode)
            assertEquals("줄바꿈 정리 모드 변경이 DataStore에 저장돼야 함", targetLineBreakMode, persisted.lineBreakMode)
            assertEquals("화면 꺼짐 방지 토글이 DataStore에 저장돼야 함", targetKeepScreenOn, persisted.keepScreenOnEnabled)
            assertEquals("화면 방향 고정이 DataStore에 저장돼야 함", targetOrientationLock, persisted.orientationLock)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
                viewModel.settingsRepository.updateLineBreakMode(originalSettings.lineBreakMode)
                viewModel.settingsRepository.updateKeepScreenOnEnabled(originalSettings.keepScreenOnEnabled)
                viewModel.settingsRepository.updateOrientationLock(originalSettings.orientationLock)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

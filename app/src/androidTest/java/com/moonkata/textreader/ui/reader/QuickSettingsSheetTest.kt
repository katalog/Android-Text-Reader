package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.PageTransitionAnimation
import com.moonkata.textreader.data.datastore.ThemePreset
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
 * 설정 시트에서 값을 바꾸면 화면(uiState)에 반영되고, 실제로 DataStore에도 저장되는지 검증한다.
 * ReaderViewModel이 프로덕션 DataStore를 그대로 쓰므로, 시작 전 원래 값을 기억해뒀다가 끝나면
 * 복원해 실기기의 실제 설정을 테스트가 영구히 바꿔버리지 않게 한다.
 */
@RunWith(AndroidJUnit4::class)
class QuickSettingsSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun changingFontMarginThemeAndTransition_updatesUiAndPersistsToDataStore() {
        val bookAsset = "Static.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }

        val targetFontSize = if (originalSettings.fontSizeSp < 30f) originalSettings.fontSizeSp + 1f else originalSettings.fontSizeSp - 1f
        val targetMargin = if (originalSettings.marginHorizontalDp < 70f) originalSettings.marginHorizontalDp + 4f else originalSettings.marginHorizontalDp - 4f
        val targetTheme = when (originalSettings.themePreset) {
            ThemePreset.LIGHT -> ThemePreset.DARK
            ThemePreset.DARK -> ThemePreset.SEPIA
            else -> ThemePreset.LIGHT
        }
        val targetTransition = when (originalSettings.pageTransitionAnimation) {
            PageTransitionAnimation.NONE -> PageTransitionAnimation.SLIDE
            PageTransitionAnimation.SLIDE -> PageTransitionAnimation.COVER
            PageTransitionAnimation.COVER -> PageTransitionAnimation.NONE
        }
        val themeLabel = mapOf(
            ThemePreset.LIGHT to "라이트",
            ThemePreset.DARK to "다크",
            ThemePreset.SEPIA to "세피아",
        ).getValue(targetTheme)
        val transitionLabel = mapOf(
            PageTransitionAnimation.NONE to "없음",
            PageTransitionAnimation.SLIDE to "슬라이드",
            PageTransitionAnimation.COVER to "덮기",
        ).getValue(targetTransition)

        try {
            waitUntilTrue { viewModel.uiState.value.settings.fontSizeSp == originalSettings.fontSizeSp }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    QuickSettingsSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            val fontSizeButtonDescription = if (targetFontSize > originalSettings.fontSizeSp) "크기 증가" else "크기 감소"
            composeTestRule.onNodeWithContentDescription(fontSizeButtonDescription).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.fontSizeSp == targetFontSize }

            val marginButtonDescription = if (targetMargin > originalSettings.marginHorizontalDp) "좌우 증가" else "좌우 감소"
            composeTestRule.onNodeWithContentDescription(marginButtonDescription).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.marginHorizontalDp == targetMargin }

            composeTestRule.onNodeWithText(themeLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.themePreset == targetTheme }

            composeTestRule.onNodeWithText(transitionLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.pageTransitionAnimation == targetTransition }

            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertEquals("폰트 크기 변경이 DataStore에 저장돼야 함", targetFontSize, persisted.fontSizeSp)
            assertEquals("여백 변경이 DataStore에 저장돼야 함", targetMargin, persisted.marginHorizontalDp)
            assertEquals("테마 변경이 DataStore에 저장돼야 함", targetTheme, persisted.themePreset)
            assertEquals("전환 애니메이션 변경이 DataStore에 저장돼야 함", targetTransition, persisted.pageTransitionAnimation)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateFontSizeSp(originalSettings.fontSizeSp)
                viewModel.settingsRepository.updateMarginHorizontalDp(originalSettings.marginHorizontalDp)
                viewModel.settingsRepository.updateThemePreset(originalSettings.themePreset)
                viewModel.settingsRepository.updatePageTransitionAnimation(originalSettings.pageTransitionAnimation)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

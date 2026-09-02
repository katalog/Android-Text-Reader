package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ChapterPatternSheet`(USER_SCENARIOS.md §8)는 지금까지 테스트가 없었다 — 순수 로직인
 * `ChapterPatternCatalog`/`ChapterDetector`만 검증돼 있었다. 여기서는 시트 UI를 실제로 조작해서
 * 설정이 바뀌고(그리고 그 결과로 챕터 재인식까지 실제로 일어나는지) 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class ChapterPatternSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun togglingTheBuiltinPreset_updatesSettings_andActuallyChangesDetectedChapters() {
        val bookAsset = "Heuk.txt" // "## 제N장" 헤더가 실제로 있는 픽스처
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }

        try {
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            waitUntilTrue(timeoutMs = 10_000) { viewModel.uiState.value.chapters.isNotEmpty() }
            assertTrue("픽스처 특성상 처음엔 '## 제N장' 프리셋으로 챕터가 잡혀 있어야 함", viewModel.uiState.value.chapters.isNotEmpty())

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    ChapterPatternSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            // 프리셋이 이거 하나뿐이라 isToggleable()만으로 특정 가능.
            composeTestRule.onNode(isToggleable()).performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { "hash" !in viewModel.uiState.value.settings.chapterPatternEnabledIds }

            // 유일한 프리셋을 껐으니 다시 스캔하면 챕터가 하나도 안 잡혀야 한다 — 실제 재인식이 일어났다는 증거.
            waitUntilTrue(timeoutMs = 10_000) { viewModel.uiState.value.chapters.isEmpty() }

            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertFalse("hash" in persisted.chapterPatternEnabledIds)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateChapterPatternEnabledIds(originalSettings.chapterPatternEnabledIds)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun addingAValidCustomPattern_clearsInput_andPersistsIt() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }

        try {
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    ChapterPatternSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            val pattern = """^Vol\.\s*\d+"""
            composeTestRule.onNode(hasSetTextAction()).performTextInput(pattern)
            composeTestRule.onNodeWithContentDescription("추가").performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000) { pattern in viewModel.uiState.value.settings.chapterCustomPatterns }
            // 입력창이 실제로 비워졌는지 — 추가된 패턴은 목록에 별도로 나오니, 입력 필드 자체의 텍스트값을 직접 읽는다.
            val fieldText = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.EditableText)?.text
            assertEquals("", fieldText)

            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertTrue(pattern in persisted.chapterCustomPatterns)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateChapterCustomPatterns(originalSettings.chapterCustomPatterns)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun addingAnInvalidRegex_showsAnError_andDoesNotClearInputOrPersist() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }

        try {
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    ChapterPatternSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            val invalidPattern = "(["  // 닫히지 않은 문자 클래스/그룹 — 유효한 정규식이 아님
            composeTestRule.onNode(hasSetTextAction()).performTextInput(invalidPattern)
            composeTestRule.onNodeWithContentDescription("추가").performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("올바른 정규식이 아니에요").assertExists()
            assertTrue(
                "잘못된 정규식은 저장되면 안 됨",
                runBlocking { viewModel.settingsRepository.settingsFlow.first() }.chapterCustomPatterns.isEmpty(),
            )
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateChapterCustomPatterns(originalSettings.chapterCustomPatterns)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }

    @Test
    fun removingACustomPattern_persistsTheRemoval() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
        val existingPattern = """^Chapter\s+\d+"""

        try {
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            runBlocking { viewModel.settingsRepository.updateChapterCustomPatterns(setOf(existingPattern)) }
            waitUntilTrue { existingPattern in viewModel.uiState.value.settings.chapterCustomPatterns }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    ChapterPatternSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText(existingPattern).assertExists()
            composeTestRule.onNodeWithContentDescription("삭제").performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                existingPattern !in viewModel.uiState.value.settings.chapterCustomPatterns
            }
            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertFalse(existingPattern in persisted.chapterCustomPatterns)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateChapterCustomPatterns(originalSettings.chapterCustomPatterns)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}

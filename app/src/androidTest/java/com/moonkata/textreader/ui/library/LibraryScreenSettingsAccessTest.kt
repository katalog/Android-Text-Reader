package com.moonkata.textreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.model.FolderEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 서재 화면에 열린 책이 없어도 설정(폰트/여백/테마/VSCode 동기화 등)을 바꿀 수 있어야 한다는 실사용
 * 피드백으로 추가 — 전에는 QuickSettingsSheet가 ReaderViewModel에 묶여있어서 책을 먼저 열지 않으면
 * 설정 화면 자체를 띄울 방법이 없었다(SettingsController 인터페이스 추출, LibraryViewModel도 구현).
 * 상단바 오른쪽 "설정" 아이콘을 눌러 시트가 뜨고, 거기서 바꾼 값이 실제로 DataStore에 저장되는지까지
 * 확인한다 — ReaderViewModel 경유가 아니라 LibraryViewModel 경유로 저장되는 새 경로라 별도 검증이
 * 필요하다.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenSettingsAccessTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val settingsRepository = ReaderSettingsRepository(application)

    @After
    fun cleanup() = runBlocking {
        settingsRepository.updateLastUsedSafTreeUri(null)
    }

    @Test
    fun settingsIconInLibrary_opensQuickSettingsSheet_andPersistsChangeToDataStore() {
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())

        val fakeRoot = Uri.parse("content://fake/settings-access-root")
        val folderBrowser = FakeFolderBrowser(
            mapOf(
                fakeRoot to listOf(
                    FolderEntry.TextFile(
                        name = "dummy.txt",
                        source = BookSource.PlainTxt(Uri.parse("content://fake/settings-access-root/dummy.txt")),
                        sizeBytes = 0,
                        lastModified = 0,
                    ),
                ),
            ),
        )

        val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, folderBrowser)
        val originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        val targetFontSize = if (originalSettings.fontSizeSp < 30f) originalSettings.fontSizeSp + 1f else originalSettings.fontSizeSp - 1f

        try {
            composeTestRule.setContent {
                MaterialTheme {
                    LibraryScreen(onOpenBook = {}, viewModel = viewModel)
                }
            }

            composeTestRule.runOnUiThread { viewModel.onRootFolderSelected(fakeRoot) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.entries.isNotEmpty() }
            composeTestRule.waitForIdle()

            // 열린 책이 없는 상태 그대로 — 설정 아이콘이 있고 누르면 시트가 뜬다.
            composeTestRule.onNodeWithContentDescription("설정").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("글자").assertExists()

            val fontSizeButtonDescription = if (targetFontSize > originalSettings.fontSizeSp) "크기 증가" else "크기 감소"
            composeTestRule.onNodeWithContentDescription(fontSizeButtonDescription).performScrollTo().performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.fontSizeSp == targetFontSize }

            val persisted = runBlocking { settingsRepository.settingsFlow.first() }
            assertEquals(
                "서재 화면에서 바꾼 설정도 LibraryViewModel을 거쳐 DataStore에 저장돼야 함",
                targetFontSize,
                persisted.fontSizeSp,
            )
        } finally {
            runBlocking {
                settingsRepository.updateFontSizeSp(originalSettings.fontSizeSp)
            }
            testDb.close()
        }
    }
}

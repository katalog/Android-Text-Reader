package com.moonkata.textreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.model.FolderEntry
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.waitUntilTrue
import com.moonkata.textreader.ui.reader.ReaderViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "폴더 선택 → txt 파일 목록 → 하나 선택 → 리더에서 실제 내용 확인"을 SAF/실제 시스템 폴더 선택창
 * 없이, 실제 소설 픽스처로 검증한다. [FakeFolderBrowser]가 폴더 목록을 대신하고, 선택된 파일 자체는
 * `androidTest/assets/books/`의 진짜 소설(file:// URI로 SAF 권한 없이도 읽힘)이라 리더까지 이어지는
 * 진짜 데이터 흐름을 그대로 탄다.
 */
@RunWith(AndroidJUnit4::class)
class LibraryFolderBrowseScenarioTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val settingsRepository = ReaderSettingsRepository(application)

    @After
    fun cleanup() = runBlocking {
        settingsRepository.updateLastUsedSafTreeUri(null)
    }

    @Test
    fun selectingFakeFolder_showsTxtFile_openingItLoadsRealNovelInReader() {
        val bookAsset = "Static.txt"
        TestBooks.assumeAvailable(bookAsset)

        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())
        val bookFile = TestBooks.copyToCache(application, bookAsset)

        val fakeRoot = Uri.parse("content://fake/root")
        val folderBrowser = FakeFolderBrowser(
            mapOf(
                fakeRoot to listOf(
                    FolderEntry.TextFile(
                        name = bookFile.name,
                        source = BookSource.PlainTxt(Uri.fromFile(bookFile)),
                        sizeBytes = bookFile.length(),
                        lastModified = bookFile.lastModified(),
                    ),
                ),
            ),
        )

        val libraryViewModel = LibraryViewModel(application, bookRepository, settingsRepository, folderBrowser)
        var openedBookId: Long? = null

        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(onOpenBook = { openedBookId = it }, viewModel = libraryViewModel)
            }
        }

        composeTestRule.runOnUiThread { libraryViewModel.onRootFolderSelected(fakeRoot) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { libraryViewModel.uiState.value.entries.isNotEmpty() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(bookFile.name).assertExists()
        composeTestRule.onNodeWithText(bookFile.name).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { openedBookId != null }

        val bookId = openedBookId
        assertNotNull("파일을 탭하면 onOpenBook이 호출되어야 함", bookId)

        val readerViewModel = ReaderViewModel(application, bookId!!, bookRepository)
        waitUntilTrue(timeoutMs = 10_000) { readerViewModel.uiState.value.fullText.isNotEmpty() }

        assertTrue(
            "리더가 읽은 본문이 실제 픽스처 소설 내용과 같아야 함",
            readerViewModel.uiState.value.fullText.contains("제1장"),
        )

        testDb.close()
    }
}

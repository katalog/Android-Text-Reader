package com.moonkata.textreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.model.FolderEntry
import com.moonkata.textreader.model.FolderSortOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 정렬 옵션이 uiState.entries의 순서를 실제로 바꾸는지 확인한다. 폴더 목록은 [FakeFolderBrowser]가
 * 주는 가짜 항목이면 충분하다 — 파일을 실제로 열어보는 게 아니라 목록 순서만 검증하는 테스트라
 * 실제 소설 픽스처는 필요 없다.
 */
@RunWith(AndroidJUnit4::class)
class LibrarySortOptionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val settingsRepository = ReaderSettingsRepository(application)

    @After
    fun cleanup() = runBlocking {
        settingsRepository.updateLastUsedSafTreeUri(null)
    }

    @Test
    fun sortOptions_reorderEntriesCorrectly() {
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())

        val fakeRoot = Uri.parse("content://fake/sort-root")
        fun entry(name: String, sizeBytes: Long, lastModified: Long) = FolderEntry.TextFile(
            name = name,
            source = BookSource.PlainTxt(Uri.parse("file:///fake/$name")),
            sizeBytes = sizeBytes,
            lastModified = lastModified,
        )
        val entries = listOf(
            entry("Banana.txt", sizeBytes = 300, lastModified = 3_000),
            entry("apple.txt", sizeBytes = 100, lastModified = 1_000),
            entry("Cherry.txt", sizeBytes = 200, lastModified = 2_000),
        )
        val folderBrowser = FakeFolderBrowser(mapOf(fakeRoot to entries))
        val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, folderBrowser)

        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(onOpenBook = {}, viewModel = viewModel)
            }
        }

        composeTestRule.runOnUiThread { viewModel.onRootFolderSelected(fakeRoot) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.entries.isNotEmpty() }

        fun namesAfter(option: FolderSortOption): List<String> {
            composeTestRule.runOnUiThread { viewModel.setSortOption(option) }
            // sortOption 변경이 combine 파이프라인을 거쳐 uiState에 실제로 반영될 때까지 기다린다
            // (waitForIdle만으로는 별도 코루틴으로 처리되는 combine 갱신이 끝났다는 보장이 없다).
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.sortOption == option }
            return viewModel.uiState.value.entries.map { it.name }
        }

        // 이름 정렬은 대소문자를 구분하지 않는다(lowercase 비교) — apple/Banana/Cherry 순.
        assertEquals(listOf("apple.txt", "Banana.txt", "Cherry.txt"), namesAfter(FolderSortOption.NAME_ASC))
        assertEquals(listOf("Cherry.txt", "Banana.txt", "apple.txt"), namesAfter(FolderSortOption.NAME_DESC))
        assertEquals(listOf("Banana.txt", "Cherry.txt", "apple.txt"), namesAfter(FolderSortOption.SIZE_DESC))
        assertEquals(listOf("apple.txt", "Cherry.txt", "Banana.txt"), namesAfter(FolderSortOption.SIZE_ASC))
        assertEquals(listOf("Banana.txt", "Cherry.txt", "apple.txt"), namesAfter(FolderSortOption.DATE_DESC))
        assertEquals(listOf("apple.txt", "Cherry.txt", "Banana.txt"), namesAfter(FolderSortOption.DATE_ASC))

        testDb.close()
    }
}

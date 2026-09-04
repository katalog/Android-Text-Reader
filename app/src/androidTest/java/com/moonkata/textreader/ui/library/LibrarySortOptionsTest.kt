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
 * Confirms that the sort option actually changes the order of uiState.entries. Fake entries from
 * [FakeFolderBrowser] are enough for the folder list — since this only verifies list order rather
 * than actually opening files, no real novel fixture is needed.
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
            // Wait until the sortOption change actually propagates to uiState through the combine
            // pipeline (waitForIdle alone doesn't guarantee the combine update, handled in a
            // separate coroutine, has finished).
            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.sortOption == option }
            return viewModel.uiState.value.entries.map { it.name }
        }

        // Name sorting is case-insensitive (compared as lowercase) — order is apple/Banana/Cherry.
        assertEquals(listOf("apple.txt", "Banana.txt", "Cherry.txt"), namesAfter(FolderSortOption.NAME_ASC))
        assertEquals(listOf("Cherry.txt", "Banana.txt", "apple.txt"), namesAfter(FolderSortOption.NAME_DESC))
        assertEquals(listOf("Banana.txt", "Cherry.txt", "apple.txt"), namesAfter(FolderSortOption.SIZE_DESC))
        assertEquals(listOf("apple.txt", "Cherry.txt", "Banana.txt"), namesAfter(FolderSortOption.SIZE_ASC))
        assertEquals(listOf("Banana.txt", "Cherry.txt", "apple.txt"), namesAfter(FolderSortOption.DATE_DESC))
        assertEquals(listOf("apple.txt", "Cherry.txt", "Banana.txt"), namesAfter(FolderSortOption.DATE_ASC))

        testDb.close()
    }
}

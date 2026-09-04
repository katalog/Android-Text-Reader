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
 * Verifies "select folder → list of txt files → pick one → confirm real content in the reader" with a
 * real novel fixture, without an SAF/real system folder picker. [FakeFolderBrowser] substitutes for
 * the folder listing, and the selected file itself is a real novel from `androidTest/assets/books/`
 * (readable via a file:// URI without SAF permission), so it rides the same real data flow all the way
 * to the reader.
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
        val bookAsset = "Heuk.txt"
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
        assertNotNull("onOpenBook should be called when the file is tapped", bookId)

        val readerViewModel = ReaderViewModel(application, bookId!!, bookRepository)
        waitUntilTrue(timeoutMs = 10_000) { readerViewModel.uiState.value.fullText.isNotEmpty() }

        assertTrue(
            "The text the reader loaded should match the actual fixture novel's content",
            readerViewModel.uiState.value.fullText.contains("제1장"),
        )

        testDb.close()
    }
}

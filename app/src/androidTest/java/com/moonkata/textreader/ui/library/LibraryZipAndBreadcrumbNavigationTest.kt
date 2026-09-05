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
import com.moonkata.textreader.testutil.waitUntilTrue
import com.moonkata.textreader.ui.reader.ReaderViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Items 7 and 8 of USER_SCENARIOS.md §1 (entering a zip, returning via breadcrumb) had no
 * automated tests until now — [LibraryFolderBrowseScenarioTest] only covers plain folder entry.
 * Uses a real zip file so that opening a file inside the zip can actually be verified (the listing
 * itself is faked via [FakeFolderBrowser], but `BookContentReader`, which reads the actual file
 * content, opens directly by URI rather than going through the folder browser, so a real zip lets
 * the real data flow be verified as-is).
 */
@RunWith(AndroidJUnit4::class)
class LibraryZipAndBreadcrumbNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val settingsRepository = ReaderSettingsRepository(application)

    @After
    fun cleanup() = runBlocking {
        settingsRepository.updateLastUsedSafTreeUri(null)
    }

    private fun realZip(entryName: String, content: String): File {
        val file = File.createTempFile("library_zip_nav_test", ".zip", application.cacheDir)
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(content.toByteArray())
            zos.closeEntry()
        }
        return file
    }

    @Test
    fun navigatingIntoAFolderThenAZip_showsItsTextEntry_andOpensTheRealContent() {
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())

        val zipContent = "ZIP_MARKER_고유내용 이것은 zip 안에 있는 실제 본문입니다."
        val zipFile = realZip("chapter1.txt", zipContent)
        val zipUri = Uri.fromFile(zipFile)

        val fakeRoot = Uri.parse("content://fake/zip-nav-root")
        val subFolderUri = Uri.parse("content://fake/zip-nav-root/series")

        val folderBrowser = FakeFolderBrowser(
            entriesByLocation = mapOf(
                fakeRoot to listOf(FolderEntry.Folder("시리즈", subFolderUri)),
                subFolderUri to listOf(FolderEntry.ZipArchive("archive.zip", zipUri, zipFile.length(), zipFile.lastModified())),
            ),
            zipEntriesByUri = mapOf(
                zipUri to listOf(
                    FolderEntry.TextFile(
                        name = "chapter1.txt",
                        source = BookSource.ZipEntryTxt(zipUri, "chapter1.txt"),
                        sizeBytes = zipContent.toByteArray().size.toLong(),
                        lastModified = zipFile.lastModified(),
                    ),
                ),
            ),
        )

        val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, folderBrowser)
        var openedBookId: Long? = null

        composeTestRule.setContent {
            MaterialTheme { LibraryScreen(onOpenBook = { openedBookId = it }, viewModel = viewModel) }
        }

        composeTestRule.runOnUiThread { viewModel.onRootFolderSelected(fakeRoot) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.entries.isNotEmpty() }

        // 1) Enter the folder
        composeTestRule.onNodeWithText("시리즈").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.entries.any { it.name == "archive.zip" } }

        // 2) Enter the zip — must go through the listZipEntries path
        composeTestRule.onNodeWithText("archive.zip").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.entries.any { it.name == "chapter1.txt" } }

        // 3) Open a file inside the zip — must read the real content from the real zip
        composeTestRule.onNodeWithText("chapter1.txt").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { openedBookId != null }

        val readerViewModel = ReaderViewModel(application, openedBookId!!, bookRepository)
        waitUntilTrue(timeoutMs = 10_000) { readerViewModel.uiState.value.fullText.isNotEmpty() }
        assertTrue(
            "Opening a file inside a zip must show the real zip entry content",
            readerViewModel.uiState.value.fullText.contains("ZIP_MARKER_고유내용"),
        )

        testDb.close()
    }

    @Test
    fun breadcrumb_navigatesBackUpAndReloadsTheCorrectListing() {
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())

        val fakeRoot = Uri.parse("content://fake/breadcrumb-root")
        val subFolderUri = Uri.parse("content://fake/breadcrumb-root/sub")
        val folderBrowser = FakeFolderBrowser(
            mapOf(
                fakeRoot to listOf(FolderEntry.Folder("하위폴더", subFolderUri)),
                subFolderUri to emptyList(),
            ),
        )

        val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, folderBrowser)

        composeTestRule.setContent {
            MaterialTheme { LibraryScreen(onOpenBook = {}, viewModel = viewModel) }
        }

        composeTestRule.runOnUiThread { viewModel.onRootFolderSelected(fakeRoot) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.entries.isNotEmpty() }
        assertEquals(1, viewModel.uiState.value.path.size)

        composeTestRule.onNodeWithText("하위폴더").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.path.size == 2 }
        assertEquals("하위폴더", viewModel.uiState.value.path.last().name)

        // Press the root breadcrumb (the very first item) to return to the top level in one step.
        // `path` updates synchronously but `entries` only after the reload coroutine finishes, so
        // waiting on `path.size` alone races with that reload — wait for the actual entries too.
        composeTestRule.runOnUiThread { viewModel.navigateToBreadcrumb(0) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.path.size == 1 &&
                viewModel.uiState.value.entries.any { it.name == "하위폴더" }
        }
        assertNotNull(
            "Returning to the root must show the subfolder entry again",
            viewModel.uiState.value.entries.find { it.name == "하위폴더" },
        )

        testDb.close()
    }
}

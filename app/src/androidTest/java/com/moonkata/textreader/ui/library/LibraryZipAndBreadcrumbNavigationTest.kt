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
 * USER_SCENARIOS.md §1의 7·8번(zip 진입, 브레드크럼 복귀)은 지금까지 자동화된 테스트가 없었다 —
 * [LibraryFolderBrowseScenarioTest]는 평범한 폴더 진입만 다룬다. zip 안 파일은 실제로 열어보는
 * 부분까지 확인하려고 진짜 zip 파일을 씀(목록 자체는 [FakeFolderBrowser]로 흉내내지만, 실제 파일
 * 내용을 읽는 `BookContentReader`는 폴더 탐색기를 거치지 않고 URI로 직접 열기 때문에 진짜 zip이면
 * 실제 데이터 흐름을 그대로 검증할 수 있다).
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

        // 1) 폴더 진입
        composeTestRule.onNodeWithText("시리즈").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.entries.any { it.name == "archive.zip" } }

        // 2) zip 진입 — listZipEntries 경로를 타야 함
        composeTestRule.onNodeWithText("archive.zip").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.entries.any { it.name == "chapter1.txt" } }

        // 3) zip 안 파일 열기 — 진짜 zip에서 진짜 내용을 읽어야 함
        composeTestRule.onNodeWithText("chapter1.txt").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { openedBookId != null }

        val readerViewModel = ReaderViewModel(application, openedBookId!!, bookRepository)
        waitUntilTrue(timeoutMs = 10_000) { readerViewModel.uiState.value.fullText.isNotEmpty() }
        assertTrue(
            "zip 안 파일을 열면 실제 zip 엔트리 내용이 보여야 함",
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

        // 루트 브레드크럼(맨 처음 항목)을 눌러 한 번에 최상위로 복귀.
        composeTestRule.runOnUiThread { viewModel.navigateToBreadcrumb(0) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.path.size == 1 }
        assertNotNull(
            "루트로 돌아오면 다시 하위폴더 항목이 보여야 함",
            viewModel.uiState.value.entries.find { it.name == "하위폴더" },
        )

        testDb.close()
    }
}

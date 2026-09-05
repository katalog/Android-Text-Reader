package com.moonkata.textreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.model.FolderEntry
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the "home folder access lost" state added alongside the "Root" → "Home" rename: a saved
 * `lastUsedSafTreeUri` whose SAF permission grant no longer exists (Auto Backup restoring the URI
 * string without the grant surviving reinstall, or the folder having been deleted) must be
 * distinguished from "no folder ever picked" — [LibraryViewModel]'s `init` block is expected to
 * check `Context.hasPersistedReadPermission` before opening a saved URI.
 *
 * `LibraryViewModel.uiState` is a `combine(...).stateIn(WhileSubscribed(5000))`, not a plain
 * `MutableStateFlow` — it only actually runs while something collects it, so every test here calls
 * `composeTestRule.setContent { LibraryScreen(...) }` (which collects via `collectAsState()`)
 * *before* reading `.uiState.value`, otherwise `.value` never advances past its initial default.
 *
 * The "permission still valid" path isn't covered here — persistable URI grants can only be
 * obtained through a real SAF picker round trip (`ACTION_OPEN_DOCUMENT_TREE`), which isn't
 * reproducible from a synthetic content URI in a test. That path is exercised indirectly by every
 * other test that calls `onRootFolderSelected` directly (bypassing the `init`-time check) and by
 * manual real-device verification.
 */
@RunWith(AndroidJUnit4::class)
class LibraryHomeFolderAccessTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val settingsRepository = ReaderSettingsRepository(application)

    private fun cleanup() = runBlocking {
        settingsRepository.updateLastUsedSafTreeUri(null)
    }

    @Test
    fun savedUriWithNoPersistedPermission_marksAccessLost_distinctFromNeverPicked() {
        cleanup()
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())
        val folderBrowser = FakeFolderBrowser(emptyMap())

        // Never granted via a real SAF picker, so contentResolver.persistedUriPermissions can't
        // possibly contain it.
        runBlocking { settingsRepository.updateLastUsedSafTreeUri("content://fake/never-granted") }

        try {
            val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, folderBrowser)

            composeTestRule.setContent {
                MaterialTheme {
                    LibraryScreen(onOpenBook = {}, viewModel = viewModel)
                }
            }

            waitUntilTrue { viewModel.uiState.value.folderAccessLost }
            assertNull("rootUri must stay null — the saved folder must not be treated as open", viewModel.uiState.value.rootUri)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Can no longer access your home folder. Please choose it again.").assertExists()
        } finally {
            cleanup()
            testDb.close()
        }
    }

    @Test
    fun selectingANewFolder_clearsAccessLost_evenThoughItStartedTrue() {
        cleanup()
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())

        // Never granted via a real SAF picker, so contentResolver.persistedUriPermissions can't
        // possibly contain it — this ViewModel is expected to start out with folderAccessLost=true.
        runBlocking { settingsRepository.updateLastUsedSafTreeUri("content://fake/never-granted") }

        val fakeRoot = Uri.parse("content://fake/recovered-root")
        val folderBrowser = FakeFolderBrowser(mapOf(fakeRoot to listOf<FolderEntry>()))

        try {
            val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, folderBrowser)

            composeTestRule.setContent {
                MaterialTheme {
                    LibraryScreen(onOpenBook = {}, viewModel = viewModel)
                }
            }

            waitUntilTrue { viewModel.uiState.value.folderAccessLost }

            // Picking a new (working) folder must clear the flag — otherwise it would linger and
            // wrongly relabel a perfectly good folder pick as "one you just recovered from".
            composeTestRule.runOnUiThread { viewModel.onRootFolderSelected(fakeRoot) }
            waitUntilTrue { viewModel.uiState.value.rootUri == fakeRoot }
            assertFalse(
                "Selecting a new folder must clear folderAccessLost, even though it started true",
                viewModel.uiState.value.folderAccessLost,
            )
        } finally {
            cleanup()
            testDb.close()
        }
    }

    @Test
    fun noSavedUriAtAll_isNotReportedAsAccessLost() {
        cleanup()
        val testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())
        val folderBrowser = FakeFolderBrowser(emptyMap())

        try {
            val viewModel = LibraryViewModel(application, bookRepository, settingsRepository, folderBrowser)

            composeTestRule.setContent {
                MaterialTheme {
                    LibraryScreen(onOpenBook = {}, viewModel = viewModel)
                }
            }
            composeTestRule.waitForIdle()

            assertTrue(
                "A fresh install (no saved URI at all) must not be reported as 'access lost'",
                !viewModel.uiState.value.folderAccessLost,
            )
            assertNull(viewModel.uiState.value.rootUri)
        } finally {
            cleanup()
            testDb.close()
        }
    }
}

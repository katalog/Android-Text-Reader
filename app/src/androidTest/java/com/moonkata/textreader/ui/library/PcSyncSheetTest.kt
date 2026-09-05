package com.moonkata.textreader.ui.library

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.R
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the actual click interactions of `PcSyncSheet` itself (the button-pressing flow) — the
 * layer underneath it, `PcSyncClient`/`computeSyncDelta`, is already verified at the
 * protocol/logic level in `PcSyncClientTest`/`PcSyncDeltaTest`, so this focuses on whether the
 * sheet accurately reflects those results on screen.
 *
 * The `pcSync*` settings use the real device's actual DataStore (same pattern as the other sheet
 * tests), so the starting values are remembered and precisely restored at the end.
 */
@RunWith(AndroidJUnit4::class)
class PcSyncSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val settingsRepository = ReaderSettingsRepository(application)
    private lateinit var testDb: AppDatabase
    private lateinit var viewModel: LibraryViewModel
    private lateinit var originalSettings: ReaderSettings

    @Before
    fun setUp() {
        originalSettings = runBlocking { settingsRepository.settingsFlow.first() }
        testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val bookRepository = BookRepository(application, testDb.bookDao())
        viewModel = LibraryViewModel(application, bookRepository, settingsRepository, FakeFolderBrowser(emptyMap()))
    }

    @After
    fun tearDown() {
        runBlocking {
            // Restore the verified fields first (this avoids the draft accidentally matching that
            // value by coincidence), then also restore the draft (pcSyncHost/Secret) for cases
            // where it differed from verified.
            settingsRepository.updatePcSyncConnection(
                originalSettings.pcSyncVerifiedHost,
                originalSettings.pcSyncVerifiedSecret,
                verified = true,
                fingerprint = originalSettings.pcSyncPinnedFingerprint,
            )
            if (originalSettings.pcSyncHost != originalSettings.pcSyncVerifiedHost ||
                originalSettings.pcSyncSecret != originalSettings.pcSyncVerifiedSecret
            ) {
                settingsRepository.updatePcSyncConnection(originalSettings.pcSyncHost, originalSettings.pcSyncSecret, verified = false)
            }
        }
        testDb.close()
    }

    // "Sync now" appears twice in the sheet, identically, as both a section title and a button
    // label (PcSyncSheet.kt), so a plain onNodeWithText matches both nodes — narrow down to just
    // the clickable one (the button).
    private fun syncButton() = composeTestRule.onNode(hasText(application.getString(R.string.pc_sync_now)).and(hasClickAction()))

    @Test
    fun unverifiedConnection_disablesSyncButton_andShowsHint() {
        runBlocking {
            settingsRepository.updatePcSyncConnection("some-host", "some-secret", verified = false)
        }
        val settings = runBlocking { settingsRepository.settingsFlow.first() }

        composeTestRule.setContent {
            MaterialTheme { PcSyncSheet(viewModel = viewModel, settings = settings, onDismiss = {}) }
        }
        composeTestRule.waitForIdle()

        syncButton().performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText(application.getString(R.string.pc_sync_test_first)).performScrollTo().assertExists()
    }

    @Test
    fun hostAndSecretMatchingAPreviouslyVerifiedConnection_showConnectedAndEnableSync() {
        runBlocking {
            settingsRepository.updatePcSyncConnection("192.168.0.42", "verified-secret", verified = true, fingerprint = "AA:BB:CC")
        }
        val settings = runBlocking { settingsRepository.settingsFlow.first() }
        assertEquals("192.168.0.42", settings.pcSyncHost)

        composeTestRule.setContent {
            MaterialTheme { PcSyncSheet(viewModel = viewModel, settings = settings, onDismiss = {}) }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(application.getString(R.string.pc_sync_connected)).assertExists()
        syncButton().performScrollTo().assertIsEnabled()
    }

    @Test
    fun editingTheSecretAfterVerification_immediatelyLosesTheConnectedState() {
        runBlocking {
            settingsRepository.updatePcSyncConnection("192.168.0.42", "verified-secret", verified = true, fingerprint = "AA:BB:CC")
        }
        val settings = runBlocking { settingsRepository.settingsFlow.first() }

        composeTestRule.setContent {
            MaterialTheme { PcSyncSheet(viewModel = viewModel, settings = settings, onDismiss = {}) }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(application.getString(R.string.pc_sync_connected)).assertExists()

        val sharedSecretLabel = application.getString(R.string.pc_sync_shared_secret)
        composeTestRule.onNodeWithText(sharedSecretLabel).performTextClearance()
        composeTestRule.onNodeWithText(sharedSecretLabel).performTextInput("different-secret")
        composeTestRule.waitForIdle()

        syncButton().performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun connectionTestFailure_showsFailureMessage_andKeepsSyncDisabled() {
        // A test that confirms connecting over a real network genuinely fails — 192.0.2.0/24
        // (TEST-NET-1) is a reserved blackhole range guaranteed not to have any server respond.
        // It must fail within PcSyncClient's connectTimeout (5 seconds).
        runBlocking {
            settingsRepository.updatePcSyncConnection("", "", verified = false)
        }
        val settings = runBlocking { settingsRepository.settingsFlow.first() }

        composeTestRule.setContent {
            MaterialTheme { PcSyncSheet(viewModel = viewModel, settings = settings, onDismiss = {}) }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(application.getString(R.string.pc_sync_host_label)).performTextInput("192.0.2.1")
        composeTestRule.onNodeWithText(application.getString(R.string.pc_sync_shared_secret)).performTextInput("whatever")
        composeTestRule.onNodeWithText(application.getString(R.string.pc_sync_test_connection)).performScrollTo().performClick()

        // The failure message was changed to append the actual cause (exception type/message)
        // verbatim (added after a real-world bug where the cause was hard to track down, see
        // .docs/SYNC_MULTIUSER_PLAN.md), so instead of a fixed phrase, only the static prefix before
        // the "%1$s" placeholder is checked here — the exact exception message can vary by
        // platform/timing. Filling the placeholder with a dummy marker and cutting at it recovers
        // just that localized prefix regardless of device language.
        val uniqueMarker = "PC_SYNC_FAILURE_MARKER"
        val failurePrefix = application.getString(R.string.pc_sync_connection_failed, uniqueMarker).substringBefore(uniqueMarker)
        composeTestRule.waitUntil(timeoutMillis = 8_000) {
            composeTestRule.onAllNodesWithText(failurePrefix, substring = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        syncButton().performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun dismissing_commitsTheUnsavedHostAndSecretDraftToSettings() {
        runBlocking {
            settingsRepository.updatePcSyncConnection("", "", verified = false)
        }
        val settings = runBlocking { settingsRepository.settingsFlow.first() }
        var dismissed = false

        composeTestRule.setContent {
            MaterialTheme { PcSyncSheet(viewModel = viewModel, settings = settings, onDismiss = { dismissed = true }) }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(application.getString(R.string.pc_sync_host_label)).performTextInput("draft-host")
        composeTestRule.onNodeWithText(application.getString(R.string.pc_sync_shared_secret)).performTextInput("draft-secret")
        composeTestRule.onNodeWithText(application.getString(R.string.pc_sync_close)).performScrollTo().performClick()

        assertTrue("onDismiss must be invoked when Close is pressed", dismissed)
        // updatePcSyncConnectionDraft commits via viewModelScope.launch, so it isn't synchronized
        // with the onDismiss call — wait until it's actually reflected in DataStore.
        waitUntilTrue { runBlocking { settingsRepository.settingsFlow.first().pcSyncHost } == "draft-host" }
        val persisted = runBlocking { settingsRepository.settingsFlow.first() }
        assertEquals("draft-host", persisted.pcSyncHost)
        assertEquals("draft-secret", persisted.pcSyncSecret)
    }
}

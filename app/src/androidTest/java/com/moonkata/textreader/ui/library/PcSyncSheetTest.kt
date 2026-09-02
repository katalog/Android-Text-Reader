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
 * `PcSyncSheet` 자체(버튼을 누르는 흐름)의 실제 클릭 상호작용을 검증한다 — 그 밑단인
 * `PcSyncClient`/`computeSyncDelta`는 `PcSyncClientTest`/`PcSyncDeltaTest`에서 이미 프로토콜/로직
 * 단위로 검증돼 있으니, 여기서는 시트가 그 결과를 화면에 정확히 반영하는지에 집중한다.
 *
 * `pcSync*` 설정은 실기기의 실제 DataStore를 그대로 쓰므로(다른 시트 테스트들과 동일한 패턴), 시작 전
 * 값을 기억해뒀다가 끝나면 정확히 복원한다.
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
            // verified 필드부터 원래 값으로 되돌린 다음(그래야 draft가 그 값과 우연히 같아지는 걸
            // 피할 수 있음), draft(pcSyncHost/Secret)가 verified와 달랐던 경우까지 마저 복원한다.
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

    // "지금 동기화"는 시트 안에 섹션 제목과 버튼 라벨 두 군데에 똑같이 쓰여있어(PcSyncSheet.kt) 단순
    // onNodeWithText로는 두 노드가 걸린다 — 클릭 가능한 쪽(버튼)만 특정한다.
    private fun syncButton() = composeTestRule.onNode(hasText("지금 동기화").and(hasClickAction()))

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
        composeTestRule.onNodeWithText("연결 테스트를 먼저 통과해야 동기화할 수 있습니다.").performScrollTo().assertExists()
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

        composeTestRule.onNodeWithText("연결됨").assertExists()
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
        composeTestRule.onNodeWithText("연결됨").assertExists()

        composeTestRule.onNodeWithText("공유 시크릿").performTextClearance()
        composeTestRule.onNodeWithText("공유 시크릿").performTextInput("different-secret")
        composeTestRule.waitForIdle()

        syncButton().performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun connectionTestFailure_showsFailureMessage_andKeepsSyncDisabled() {
        // 진짜 네트워크로 접속 자체가 안 되는 걸 확인하는 테스트 — 192.0.2.0/24(TEST-NET-1)는 예약된
        // 블랙홀 대역이라 어떤 서버도 응답하지 않는 게 보장된다. PcSyncClient의 connectTimeout(5초)
        // 안에 실패로 끝나야 한다.
        runBlocking {
            settingsRepository.updatePcSyncConnection("", "", verified = false)
        }
        val settings = runBlocking { settingsRepository.settingsFlow.first() }

        composeTestRule.setContent {
            MaterialTheme { PcSyncSheet(viewModel = viewModel, settings = settings, onDismiss = {}) }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("PC 주소 (컴퓨터 이름 또는 IP)").performTextInput("192.0.2.1")
        composeTestRule.onNodeWithText("공유 시크릿").performTextInput("whatever")
        composeTestRule.onNodeWithText("연결 테스트").performScrollTo().performClick()

        composeTestRule.waitUntil(timeoutMillis = 8_000) {
            composeTestRule.onAllNodesWithText("연결 실패 — 주소/시크릿을 확인하세요")
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

        composeTestRule.onNodeWithText("PC 주소 (컴퓨터 이름 또는 IP)").performTextInput("draft-host")
        composeTestRule.onNodeWithText("공유 시크릿").performTextInput("draft-secret")
        composeTestRule.onNodeWithText("닫기").performScrollTo().performClick()

        assertTrue("닫기를 누르면 onDismiss가 호출돼야 함", dismissed)
        // updatePcSyncConnectionDraft는 viewModelScope.launch로 커밋해 onDismiss 호출과 동기화돼 있지
        // 않다 — 실제로 DataStore에 반영될 때까지 기다린다.
        waitUntilTrue { runBlocking { settingsRepository.settingsFlow.first().pcSyncHost } == "draft-host" }
        val persisted = runBlocking { settingsRepository.settingsFlow.first() }
        assertEquals("draft-host", persisted.pcSyncHost)
        assertEquals("draft-secret", persisted.pcSyncSecret)
    }
}

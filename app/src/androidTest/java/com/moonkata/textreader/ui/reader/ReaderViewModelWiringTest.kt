package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ReaderViewModel`을 화면 렌더링 없이 직접 구동해 두 가지, 지금까지 테스트가 없던 배선을 확인한다
 * (USER_SCENARIOS.md §5, §14):
 * 1. 세로 스크롤 모드에서는 `next()`/`previous()`가 `Paginator` 계산이 아니라 `navEvents`로
 *    `RequestNextPage`/`RequestPreviousPage`(또는 챕터점프 모드면 `JumpToOffset`)를 방출해야 한다 —
 *    실제 스크롤은 `ReaderScrollContent`가 그 이벤트를 받아서 하므로, 뷰모델 쪽은 "올바른 이벤트를
 *    보내는지"까지만 이 레벨에서 검증 가능하다.
 * 2. `flushPendingPosition()`은 500ms 디바운스를 기다리지 않고 즉시 Room에 반영해야 한다(화면
 *    이탈/백그라운드 시점에 위치 유실을 막는 경로).
 */
@RunWith(AndroidJUnit4::class)
class ReaderViewModelWiringTest {

    private val bookAsset = "Heuk.txt"

    private fun setUp(): Triple<Application, AppDatabase, BookRepository> {
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        return Triple(application, db, bookRepository)
    }

    @Test
    fun verticalScrollMode_nextAndPrevious_emitRequestPageNavEvents_insteadOfComputingPages() = runBlocking {
        val (application, db, bookRepository) = setUp()
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = TestBooks.insertBook(application, bookRepository, bookAsset)

        val originalSettings = settingsRepository.settingsFlow.first()
        settingsRepository.updatePageTurnMode(PageTurnMode.VERTICAL_SCROLL)
        settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
        settingsRepository.updateChapterJumpEnabled(false)

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            waitUntilTrue {
                val s = viewModel.uiState.value.settings
                s.pageTurnMode == PageTurnMode.VERTICAL_SCROLL && !s.chapterJumpEnabled
            }

            // navEvents는 replay가 없는 SharedFlow라, next()를 부르기 전에 구독이 이미 걸려 있어야
            // 한다. CoroutineStart.UNDISPATCHED로 async를 시작하면 첫 suspend 지점(first()의 구독)
            // 까지는 그 자리에서 동기적으로 실행되므로, 바로 다음 줄의 next() 호출 전에 구독이 확실히
            // 걸려 있음이 보장된다 — waitUntilTrue(Thread.sleep 기반 폴링)를 같은 단일 스레드
            // runBlocking 이벤트 루프 위에서 함께 쓰면, 그 sleep이 이 코루틴이 실행될 차례를 막아버려
            // 영원히 이벤트를 못 받는 문제가 있었다(실제로 처음 겪은 실패 원인).
            val nextEventDeferred = async(start = CoroutineStart.UNDISPATCHED) { viewModel.navEvents.first() }
            viewModel.next()
            assertEquals(ReaderNavEvent.RequestNextPage, withTimeout(5_000) { nextEventDeferred.await() })

            val previousEventDeferred = async(start = CoroutineStart.UNDISPATCHED) { viewModel.navEvents.first() }
            viewModel.previous()
            assertEquals(ReaderNavEvent.RequestPreviousPage, withTimeout(5_000) { previousEventDeferred.await() })
        } finally {
            settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
            settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
            settingsRepository.updateChapterJumpEnabled(originalSettings.chapterJumpEnabled)
            db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
        }
    }

    @Test
    fun verticalScrollMode_withChapterJumpEnabled_emitsJumpToOffsetInsteadOfRequestPage() = runBlocking {
        val (application, db, bookRepository) = setUp()
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = TestBooks.insertBook(application, bookRepository, bookAsset)

        val originalSettings = settingsRepository.settingsFlow.first()
        settingsRepository.updatePageTurnMode(PageTurnMode.VERTICAL_SCROLL)
        settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
        settingsRepository.updateChapterJumpEnabled(true)

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }
            waitUntilTrue(timeoutMs = 10_000) { viewModel.uiState.value.chapters.isNotEmpty() }
            waitUntilTrue {
                val s = viewModel.uiState.value.settings
                s.pageTurnMode == PageTurnMode.VERTICAL_SCROLL && s.chapterJumpEnabled
            }

            val nextEventDeferred = async(start = CoroutineStart.UNDISPATCHED) { viewModel.navEvents.first() }
            viewModel.next()
            val event = withTimeout(5_000) { nextEventDeferred.await() }
            assertTrue("챕터점프 모드에서는 JumpToOffset이 나가야 함: $event", event is ReaderNavEvent.JumpToOffset)
            event as ReaderNavEvent.JumpToOffset
            assertEquals(false, event.animate)
            assertEquals(event.offset, viewModel.uiState.value.currentOffset)
        } finally {
            settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
            settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
            settingsRepository.updateChapterJumpEnabled(originalSettings.chapterJumpEnabled)
            db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
        }
    }

    @Test
    fun flushPendingPosition_persistsImmediately_withoutWaitingForTheDebounceTimer() = runBlocking {
        val (application, db, bookRepository) = setUp()
        val settingsRepository = ReaderSettingsRepository(application)
        val bookId = TestBooks.insertBook(application, bookRepository, bookAsset)

        val originalSettings = settingsRepository.settingsFlow.first()
        settingsRepository.updatePageTurnMode(PageTurnMode.HORIZONTAL_PAGE)
        settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF)
        settingsRepository.updateChapterJumpEnabled(false)

        try {
            val viewModel = ReaderViewModel(application, bookId, bookRepository)
            waitUntilTrue { viewModel.uiState.value.paragraphs.isNotEmpty() }

            val targetOffset = 4321
            viewModel.updateCurrentOffset(targetOffset)
            // 디바운스(500ms)가 끝나기 훨씬 전에 강제로 flush — ON_STOP/화면 이탈 때와 같은 경로.
            // flushPendingPosition 내부도 viewModelScope.launch라 비동기라, 실제 Room 반영을 기다린다
            // (500ms 디바운스 타이머 자체보다 훨씬 짧게 끝나야 "즉시 반영"이라는 계약이 증명됨).
            viewModel.flushPendingPosition()
            waitUntilTrue(timeoutMs = 3_000) {
                runBlocking { bookRepository.observeBook(bookId).first()?.lastReadCharOffset } == targetOffset
            }

            val persisted = bookRepository.observeBook(bookId).first()
            assertEquals(
                "flushPendingPosition은 디바운스를 기다리지 않고 즉시 Room에 반영해야 함",
                targetOffset,
                persisted?.lastReadCharOffset,
            )
        } finally {
            settingsRepository.updatePageTurnMode(originalSettings.pageTurnMode)
            settingsRepository.updateAutoAdvanceMode(originalSettings.autoAdvanceMode)
            settingsRepository.updateChapterJumpEnabled(originalSettings.chapterJumpEnabled)
            db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
        }
    }
}

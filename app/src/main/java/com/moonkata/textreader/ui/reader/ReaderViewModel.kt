package com.moonkata.textreader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.compose.ui.text.TextMeasurer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.LineBreakMode
import com.moonkata.textreader.data.datastore.OrientationLock
import com.moonkata.textreader.data.datastore.PageTransitionAnimation
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.datastore.SwipeTurnMode
import com.moonkata.textreader.data.datastore.ThemePreset
import com.moonkata.textreader.data.datastore.TouchTurnMode
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.db.BookEntity
import com.moonkata.textreader.data.font.FontCatalogEntry
import com.moonkata.textreader.data.font.FontDownloadManager
import com.moonkata.textreader.data.font.FontDownloadState
import com.moonkata.textreader.data.parser.ChapterDetector
import com.moonkata.textreader.data.parser.ChapterPatternCatalog
import com.moonkata.textreader.data.parser.ChapterJumpNavigator
import com.moonkata.textreader.data.parser.PaginationParams
import com.moonkata.textreader.data.parser.Paginator
import com.moonkata.textreader.data.parser.TextReflower
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.data.sync.ReadingPositionSyncClient
import com.moonkata.textreader.data.sync.SupabaseConfig
import com.moonkata.textreader.data.sync.relativePathFromSafDocumentUri
import com.moonkata.textreader.model.Chapter
import com.moonkata.textreader.model.PageBreak
import com.moonkata.textreader.model.Paragraph
import com.moonkata.textreader.model.SearchResult
import com.moonkata.textreader.tts.AutoPageTurnController
import com.moonkata.textreader.tts.TtsController
import com.moonkata.textreader.ui.SettingsController
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class ReaderUiState(
    val isLoading: Boolean = true,
    val book: BookEntity? = null,
    val fullText: String = "",
    val paragraphs: List<Paragraph> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    /** 페이지 모드에서 지금 화면에 보여줄 페이지 하나 — 책 전체 페이지 목록은 더 이상 들고 있지 않는다. */
    val currentPage: PageBreak? = null,
    val currentOffset: Int = 0,
    val settings: ReaderSettings = ReaderSettings(),
    /** PC(VSCode)나 다른 기기가 이 책을 더 멀리 읽었을 때의 오프셋 — null이면 팝업 없음.
     * .docs/VSCODE_SYNC_PLAN.md §4 참고. */
    val externalFurtherOffset: Int? = null,
)

sealed class ReaderNavEvent {
    data class JumpToOffset(val offset: Int, val animate: Boolean) : ReaderNavEvent()
    data object RequestNextPage : ReaderNavEvent()
    data object RequestPreviousPage : ReaderNavEvent()
}

/**
 * [bookRepository]는 생성자로 주입받는다 — 테스트가 프로덕션 싱글톤 `AppDatabase` 대신 격리된
 * 인메모리 DB로 갈아끼울 수 있게 하기 위함이다(그렇지 않으면 테스트가 만든 bookId가 실제 앱 DB의
 * 전혀 다른 책 행을 가리켜, 그 책이 진짜 SAF 파일이면 권한 오류로 깨진다). 프로덕션 조립은
 * [ReaderViewModelFactory]가 담당한다.
 */
class ReaderViewModel(
    application: Application,
    private val bookId: Long,
    private val bookRepository: BookRepository,
) : AndroidViewModel(application), SettingsController {

    val settingsRepository = ReaderSettingsRepository(application)
    private val fontDownloadManager = FontDownloadManager(application)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    private val _navEvents = MutableSharedFlow<ReaderNavEvent>(extraBufferCapacity = 4)
    val navEvents: SharedFlow<ReaderNavEvent> = _navEvents

    private var ttsController: TtsController? = null
    private var ttsPendingRange: Pair<Int, Int>? = null
    private val ttsChunkChars = 500

    /**
     * 같은 위치에서 이만큼(5분) 안 움직이면 원격에도 체크포인트를 남긴다 — §원격 동기화 참고.
     * 원래 1분이었는데, 사용자가 늘어날 걸 감안해 화면 이탈 시 즉시 반영 경로는 그대로 두고 이
     * 간격만 늘려 원격 쓰기 빈도를 줄였다(SYNC_MULTIUSER_PLAN.md 스테이지 2).
     */
    private val remoteSyncIdleMs = 300_000L

    /**
     * 원격 조회(§checkRemoteAndMaybeNotify) 최소 간격 — 화면이 짧은 시간 안에 백그라운드↔포그라운드를
     * 반복하면(예: 최근 앱 목록을 열었다 바로 닫기) ON_START가 그때마다 발생해 중복 조회가 나갈 수
     * 있어, 마지막 조회 후 이 시간 안에는 다시 조회하지 않는다(SYNC_MULTIUSER_PLAN.md 스테이지 2).
     */
    private val remoteFetchCooldownMs = 30_000L

    /** 마지막으로 원격 조회를 실제로 시도한 시각(ms) — §remoteFetchCooldownMs 참고. */
    private var lastRemoteFetchAtMs = 0L

    /**
     * 원격이 이만큼(문자 수) 넘게 앞서 있을 때만 "더 읽으셨어요" 팝업을 띄운다 — VSCode 커서 오프셋과
     * 안드로이드 페이지 오프셋은 애초에 가리키는 단위가 달라서(문자 단위 vs 페이지 시작 지점) 실제로는
     * 같은 곳을 읽고 있어도 수백 자 정도 어긋날 수 있다. VSCode 쪽과 동일 값(§원격 동기화 참고).
     */
    private val minOffsetDiffToNotify = 500

    private val autoPageTurnController = AutoPageTurnController(viewModelScope) { advance() }

    private var lastPaginationKey: String? = null
    private var pageComputeJob: Job? = null
    private var lastTextMeasurer: TextMeasurer? = null
    private var lastPaginationParams: PaginationParams? = null
    private var writePositionJob: Job? = null

    /** 정방향으로 넘겨온 페이지들 — "이전"에서 다시 계산할 필요 없이 그대로 팝해서 쓴다. */
    private val pageHistory = ArrayDeque<PageBreak>()

    /**
     * 챕터 점프로 마지막에 이동한 목표 오프셋 — 페이지가 실제로 정착(settle)하면
     * currentOffset이 그 페이지의 시작 오프셋(목표보다 앞일 수 있음)으로 재조정되는데,
     * 그 값을 그대로 다음 breakpoint 계산의 기준으로 쓰면 같은 지점이 다시 잡혀 탭이
     * 한 번 더 필요해진다. 실제 목표 오프셋을 별도로 기억해 기준으로 삼아 이를 막는다.
     */
    private var lastChapterJumpOffset: Int? = null

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                val previous = _uiState.value.settings
                _uiState.update { it.copy(settings = settings) }
                if (settings.lineBreakMode != previous.lineBreakMode && _uiState.value.fullText.isNotEmpty()) {
                    reflowParagraphs(settings.lineBreakMode)
                }
                val patternsChanged = settings.chapterPatternEnabledIds != previous.chapterPatternEnabledIds ||
                    settings.chapterCustomPatterns != previous.chapterCustomPatterns
                if (patternsChanged && _uiState.value.fullText.isNotEmpty()) {
                    redetectChapters(settings)
                }
                handleAutoAdvanceModeChange(settings)
            }
        }
        viewModelScope.launch {
            bookRepository.observeBook(bookId).collect { book ->
                if (book != null) _uiState.update { it.copy(book = book) }
            }
        }
        loadBook()
    }

    private fun loadBook() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            var book = bookRepository.observeBook(bookId).first() ?: return@launch
            book = backfillRelativePathIfNeeded(book)
            val result = bookRepository.openBookContent(book)
            val settings = _uiState.value.settings
            val paragraphs = withContext(Dispatchers.Default) {
                TextReflower.reflow(result.text, settings.lineBreakMode)
            }
            bookRepository.markOpened(bookId, result.text.length, result.encoding)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    fullText = result.text,
                    paragraphs = paragraphs,
                    currentOffset = book.lastReadCharOffset.coerceIn(0, result.text.length),
                )
            }
            // 원격에 이미 반영된 값의 기준선 — 이 값과 같은 오프셋으로는 다시 안 올린다(§원격 동기화 참고).
            lastRemoteSyncedOffset = book.lastReadCharOffset
            // 챕터 탐지(전체 텍스트 줄 단위 정규식 스캔)는 첫 페이지 표시에 필요 없다 —
            // 로딩 게이트에서 빼서 백그라운드로 돌리고, 끝나는 대로 chapters만 나중에 채운다.
            redetectChapters(settings)
            // state.book이 아니라 방금 백필까지 마친 book을 직접 써야 한다 — observeBook Flow가 새
            // relativePath를 _uiState에 반영하기 전에 이 체크가 먼저 돌 수 있어서(타이밍 레이스).
            checkRemoteAndMaybeNotify(book.relativePath, book.lastReadCharOffset, settings)
        }
    }

    /**
     * VSCode 등 다른 기기가 이 책을 더 멀리 읽었는지 조회 — 책을 열 때(loadBook) 한 번, 그리고 리더
     * 화면이 다시 보이게 될 때(화면 잠금 해제, 다른 앱에서 돌아옴 등, §onReaderResumed)마다 다시 확인한다.
     * 읽는 도중 계속 폴링하지는 않는다 — "화면을 다시 보게 된 시점"에만 다른 기기 위치가 궁금해지므로.
     */
    private fun checkExternalFurtherPositionNow() {
        val state = _uiState.value
        val book = state.book
        if (state.isLoading || book == null) return
        checkRemoteAndMaybeNotify(book.relativePath, state.currentOffset, state.settings)
    }

    private fun checkRemoteAndMaybeNotify(relativePath: String, localOffset: Int, settings: ReaderSettings) {
        if (relativePath.isEmpty()) return
        val client = syncClientOrNull(settings) ?: return
        val now = System.currentTimeMillis()
        if (now - lastRemoteFetchAtMs < remoteFetchCooldownMs) return
        lastRemoteFetchAtMs = now
        viewModelScope.launch {
            val remote = client.fetch(relativePath) ?: return@launch
            if (remote.charOffset - _uiState.value.currentOffset > minOffsetDiffToNotify) {
                _uiState.update { it.copy(externalFurtherOffset = remote.charOffset) }
            }
        }
    }

    /**
     * `LibraryViewModel`이 폴더 브라우징 중(BrowseLocation 스택)에만 relativePath를 계산해서 넘기는데,
     * "이어서 읽기" 다이얼로그로 열거나 이미 등록된 책을 다시 여는 경로는 그 스택을 안 거쳐서
     * relativePath가 계속 비어있게 되는 문제가 실사용 중 확인됐다 — "니치한 재방문"이라 여겼던
     * §열린 질문 6의 전제와 달리 "이어서 읽기"가 오히려 제일 흔한 진입 경로였다. 책을 열 때마다
     * relativePath가 비어있으면 SAF 문서 URI에서 역산하는 폴백으로 채운다(RelativePath.kt 참고).
     */
    private suspend fun backfillRelativePathIfNeeded(book: BookEntity): BookEntity {
        if (book.relativePath.isNotEmpty()) return book
        val source = BookSource.fromStoredString(book.documentUri)
        if (source !is BookSource.PlainTxt) return book
        val treeUriString = settingsRepository.settingsFlow.first().lastUsedSafTreeUri ?: return book
        val relativePath = relativePathFromSafDocumentUri(source.uri, Uri.parse(treeUriString)) ?: return book
        bookRepository.updateRelativePath(bookId, relativePath)
        return book.copy(relativePath = relativePath)
    }

    /** 리더 화면이 다시 화면에 보이게 됐을 때(화면 켜짐, 다른 앱에서 복귀 등) 호출 — ON_START에 연결. */
    fun onReaderResumed() {
        checkExternalFurtherPositionNow()
    }

    /**
     * 시크릿을 입력만 하고 "연결 테스트"를 눌러본 적 없으면(혹은 마지막 검증 이후 값이 바뀌었으면)
     * null — 검증 안 된 시크릿으로 계속 실패할 요청을 조용히 반복해서 보내는 걸 막는다. 설정 화면의
     * "연결됨" 배지가 곧 이 기능이 실제로 켜져 있다는 뜻과 정확히 같아야 사용자가 헷갈리지 않는다.
     */
    private fun syncClientOrNull(settings: ReaderSettings): ReadingPositionSyncClient? {
        if (settings.supabaseSharedSecret.isBlank()) return null
        if (settings.supabaseSharedSecret != settings.supabaseVerifiedSecret) return null
        return ReadingPositionSyncClient(SupabaseConfig.URL, SupabaseConfig.PUBLISHABLE_KEY, settings.supabaseSharedSecret)
    }

    /** 설정 화면 "연결 테스트" 버튼 — 성공하면 시크릿과 함께 검증 상태를 같이 커밋한다. */
    override suspend fun testSupabaseConnection(secret: String): Boolean {
        if (secret.isBlank()) return false
        val client = ReadingPositionSyncClient(SupabaseConfig.URL, SupabaseConfig.PUBLISHABLE_KEY, secret)
        val success = client.testConnection()
        if (success) settingsRepository.updateSupabaseSharedSecret(secret, verifiedSecret = secret)
        return success
    }

    fun dismissExternalPositionPrompt() {
        _uiState.update { it.copy(externalFurtherOffset = null) }
    }

    fun jumpToExternalPosition() {
        val offset = _uiState.value.externalFurtherOffset ?: return
        _uiState.update { it.copy(externalFurtherOffset = null) }
        jumpToOffset(offset)
    }

    private suspend fun reflowParagraphs(mode: LineBreakMode) {
        val text = _uiState.value.fullText
        val paragraphs = withContext(Dispatchers.Default) { TextReflower.reflow(text, mode) }
        _uiState.update { it.copy(paragraphs = paragraphs) }
        lastPaginationKey = null
    }

    private suspend fun redetectChapters(settings: ReaderSettings) {
        val text = _uiState.value.fullText
        val patterns = ChapterPatternCatalog.buildRegexList(settings.chapterPatternEnabledIds, settings.chapterCustomPatterns)
        val chapters = withContext(Dispatchers.Default) { ChapterDetector.detect(text, patterns) }
        _uiState.update { it.copy(chapters = chapters) }
    }

    fun onViewportMeasured(textMeasurer: TextMeasurer, params: PaginationParams) {
        val state = _uiState.value
        if (state.settings.pageTurnMode != PageTurnMode.HORIZONTAL_PAGE) return
        if (state.paragraphs.isEmpty()) return
        val key = "${params.fontFamily}|${params.fontSizeSp}|${params.lineHeightMultiplier}|" +
            "${params.letterSpacingSp}|${params.contentWidthPx}|${params.contentHeightPx}|${state.settings.lineBreakMode}"
        if (key == lastPaginationKey) return
        lastPaginationKey = key
        lastTextMeasurer = textMeasurer
        lastPaginationParams = params
        // 폰트/여백/화면 크기가 바뀌면 줄바꿈이 달라져 페이지 경계 자체가 달라진다 — 지금 읽던 위치를
        // 기준으로 현재 페이지만 새로 계산한다. 방문 이력은 이전 페이지 경계 기준으로 쌓인 것이라 더 이상
        // 유효하지 않으므로 비운다.
        pageHistory.clear()
        computeCurrentPageAt(state.currentOffset)
    }

    /** anchorOffset부터 시작하는 페이지 하나만 계산해 currentPage에 반영한다 — 초기 로드/설정 변경 시 사용. */
    private fun computeCurrentPageAt(anchorOffset: Int) {
        val textMeasurer = lastTextMeasurer ?: return
        val params = lastPaginationParams ?: return
        val state = _uiState.value
        if (state.paragraphs.isEmpty()) return

        pageComputeJob?.cancel()
        pageComputeJob = viewModelScope.launch(Dispatchers.Default) {
            val page = Paginator.paginateFrom(state.fullText, state.paragraphs, anchorOffset, textMeasurer, params, maxPages = 1).firstOrNull()
            _uiState.update { it.copy(currentPage = page) }
        }
    }

    /** 페이지 모드에서 offset부터 시작하는 페이지로 즉시 이동한다 — 검색/목차/북마크/챕터 점프 공용. */
    private fun jumpToPageAt(offset: Int) {
        pageHistory.clear()
        computeCurrentPageAt(offset)
    }

    /** 페이지 모드에서 한 페이지 앞으로 — 현재 페이지를 방문 이력에 쌓고, 그 끝부터 한 페이지만 계산한다. */
    private fun advancePageForward() {
        val textMeasurer = lastTextMeasurer ?: return
        val params = lastPaginationParams ?: return
        val state = _uiState.value
        val current = state.currentPage ?: return
        if (current.endOffset >= state.fullText.length) return // 이미 마지막 페이지

        pageHistory.addLast(current)
        pageComputeJob?.cancel()
        pageComputeJob = viewModelScope.launch(Dispatchers.Default) {
            val next = Paginator.paginateFrom(state.fullText, state.paragraphs, current.endOffset, textMeasurer, params, maxPages = 1).firstOrNull()
            if (next != null) {
                _uiState.update { it.copy(currentPage = next) }
                updateCurrentOffset(next.startOffset)
            }
        }
    }

    /**
     * 페이지 모드에서 한 페이지 뒤로. 정방향으로 넘겨오면서 쌓인 방문 이력이 있으면 그대로 팝해서
     * 쓴다(정확·즉시). 점프 직후처럼 이력이 없으면 [Paginator.onePageEndingAt]으로 역산 추정한다.
     */
    private fun advancePageBackward() {
        val textMeasurer = lastTextMeasurer ?: return
        val params = lastPaginationParams ?: return
        val state = _uiState.value
        val current = state.currentPage ?: return
        if (current.startOffset <= 0) return // 이미 첫 페이지

        val fromHistory = pageHistory.removeLastOrNull()
        if (fromHistory != null) {
            _uiState.update { it.copy(currentPage = fromHistory) }
            updateCurrentOffset(fromHistory.startOffset)
            return
        }

        val referenceSpan = (current.endOffset - current.startOffset).coerceAtLeast(1)
        pageComputeJob?.cancel()
        pageComputeJob = viewModelScope.launch(Dispatchers.Default) {
            val prev = Paginator.onePageEndingAt(state.fullText, state.paragraphs, current.startOffset, textMeasurer, params, referenceSpan)
            if (prev != null) {
                _uiState.update { it.copy(currentPage = prev) }
                updateCurrentOffset(prev.startOffset)
            }
        }
    }

    /** 아직 DB에 쓰이지 않은 마지막 오프셋 — 디바운스 타이머가 끝나기 전에 화면을 벗어나도 유실되지 않게 붙잡아 둔다. */
    private var pendingWriteOffset: Int? = null

    fun updateCurrentOffset(offset: Int, persist: Boolean = true) {
        _uiState.update { it.copy(currentOffset = offset) }
        if (persist) {
            schedulePositionWrite(offset)
            scheduleRemoteSyncCheckpoint(offset)
        }
    }

    private fun schedulePositionWrite(offset: Int) {
        pendingWriteOffset = offset
        writePositionJob?.cancel()
        writePositionJob = viewModelScope.launch {
            delay(500)
            flushPendingPosition()
        }
    }

    /**
     * 디바운스 타이머를 기다리지 않고 마지막 읽기 위치를 로컬(Room)에 즉시 저장한다.
     * 화면이 백그라운드로 가거나(ON_STOP) 리더를 벗어날 때 호출해 위치 유실을 막는다.
     * 원격(Supabase) 동기화는 별도 경로(§원격 동기화, [syncNowToRemote])로 처리한다 — 로컬 저장은
     * 페이지/문단이 바뀔 때마다 자주 일어나도 비용이 거의 없지만, 원격은 네트워크 호출이라 매번 올리면
     * 낭비이므로 트리거를 분리했다.
     */
    fun flushPendingPosition() {
        val offset = consumePendingOffset() ?: return
        viewModelScope.launch { persistPositionLocal(offset) }
    }

    private fun consumePendingOffset(): Int? {
        val offset = pendingWriteOffset ?: return null
        pendingWriteOffset = null
        writePositionJob?.cancel()
        return offset
    }

    private suspend fun persistPositionLocal(offset: Int) {
        val total = _uiState.value.fullText.length
        val progress = if (total > 0) offset.toFloat() / total else 0f
        bookRepository.updateReadPosition(bookId, offset, progress)
    }

    // --- 원격(Supabase) 동기화 ---
    // 페이지/문단 이동마다 매번 올리면 낭비라, 아래 두 경로로만 원격에 반영한다:
    // 1) 같은 위치에서 remoteSyncIdleMs(5분) 이상 머무르면(체크포인트)
    // 2) 리더 화면을 벗어나는 시점(뒤로가기 → onCleared / 화면 꺼짐·홈·다른 앱 전환 → ON_STOP) 즉시

    private var remoteCheckpointJob: Job? = null

    /** 마지막으로 원격에 실제로 반영한 오프셋 — 그 값과 같으면 다시 안 올려서 불필요한 호출을 줄인다. */
    private var lastRemoteSyncedOffset: Int? = null

    private fun scheduleRemoteSyncCheckpoint(offset: Int) {
        remoteCheckpointJob?.cancel()
        remoteCheckpointJob = viewModelScope.launch {
            delay(remoteSyncIdleMs)
            pushRemoteSync(offset)
        }
    }

    /** 뷰어를 벗어나는 시점에 호출 — 체크포인트 타이머를 기다리지 않고 지금 위치를 바로 원격에 반영한다. */
    fun syncNowToRemote() {
        remoteCheckpointJob?.cancel()
        pushRemoteSync(_uiState.value.currentOffset)
    }

    private fun pushRemoteSync(offset: Int) {
        if (offset == lastRemoteSyncedOffset) return
        val state = _uiState.value
        val book = state.book
        val relativePath = book?.relativePath.orEmpty()
        if (relativePath.isEmpty()) return
        val client = syncClientOrNull(state.settings) ?: return
        lastRemoteSyncedOffset = offset
        // GlobalScope로 일부러 분리 — viewModelScope에 묶으면 onCleared 직후(runBlocking으로 로컬 저장을
        // 마치는 바로 그 시점) viewModelScope가 이미 취소되어 있어 이 업서트가 시작도 못 하고 사라진다.
        // 화면을 떠나는 순간의 위치야말로 동기화가 가장 필요한 시점이라, 최선 노력(best-effort)으로라도
        // 뷰모델 생명주기와 무관하게 나가야 한다.
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) { client.upsert(relativePath, offset, book?.detectedEncoding) }
    }

    fun jumpToOffset(offset: Int) {
        lastChapterJumpOffset = null
        updateCurrentOffset(offset)
        if (_uiState.value.settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE) {
            jumpToPageAt(offset)
        } else {
            _navEvents.tryEmit(ReaderNavEvent.JumpToOffset(offset, animate = true))
        }
    }

    // --- 챕터 점프를 반영한 다음/이전 ---
    fun next() {
        val state = _uiState.value
        if (state.settings.chapterJumpEnabled && state.chapters.isNotEmpty()) {
            val breakpoints = ChapterJumpNavigator.breakpoints(state.chapters, state.fullText.length, state.settings.chapterJumpDivisions)
            val anchor = maxOf(state.currentOffset, lastChapterJumpOffset ?: Int.MIN_VALUE)
            val target = ChapterJumpNavigator.nextBreakpoint(breakpoints, anchor)
            if (target == null) {
                // 더 이상 점프할 지점이 없음(마지막 챕터 이후) — 그냥 페이지 넘김으로 폴백해 탭이 "죽지" 않게 한다.
                advanceNormally(state)
                return
            }
            lastChapterJumpOffset = target
            updateCurrentOffset(target)
            if (state.settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE) {
                jumpToPageAt(target)
            } else {
                _navEvents.tryEmit(ReaderNavEvent.JumpToOffset(target, animate = false))
            }
        } else {
            advanceNormally(state)
        }
    }

    fun previous() {
        val state = _uiState.value
        if (state.settings.chapterJumpEnabled && state.chapters.isNotEmpty()) {
            val breakpoints = ChapterJumpNavigator.breakpoints(state.chapters, state.fullText.length, state.settings.chapterJumpDivisions)
            val anchor = minOf(state.currentOffset, lastChapterJumpOffset ?: Int.MAX_VALUE)
            val target = ChapterJumpNavigator.previousBreakpoint(breakpoints, anchor)
            if (target == null) {
                retreatNormally(state)
                return
            }
            lastChapterJumpOffset = target
            updateCurrentOffset(target)
            if (state.settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE) {
                jumpToPageAt(target)
            } else {
                _navEvents.tryEmit(ReaderNavEvent.JumpToOffset(target, animate = false))
            }
        } else {
            retreatNormally(state)
        }
    }

    private fun advanceNormally(state: ReaderUiState) {
        if (state.settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE) {
            advancePageForward()
        } else {
            _navEvents.tryEmit(ReaderNavEvent.RequestNextPage)
        }
    }

    private fun retreatNormally(state: ReaderUiState) {
        if (state.settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE) {
            advancePageBackward()
        } else {
            _navEvents.tryEmit(ReaderNavEvent.RequestPreviousPage)
        }
    }

    // --- 검색 ---
    /** 검색 시트를 닫았다 다시 열어도 마지막 검색 결과를 이어서 볼 수 있도록 기억해둔다. */
    var lastSearchQuery: String = ""
        private set
    var lastSearchResults: List<SearchResult> = emptyList()
        private set

    fun search(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val text = _uiState.value.fullText
        val results = mutableListOf<SearchResult>()
        var idx = text.indexOf(query, 0, ignoreCase = true)
        while (idx >= 0 && results.size < 200) {
            val snippetStart = (idx - 20).coerceAtLeast(0)
            val snippetEnd = (idx + query.length + 20).coerceAtMost(text.length)
            // 스니펫이 문단 경계(빈 줄)를 걸치면 개행이 그대로 남아 미리보기의 maxLines를
            // 빈 줄로 다 써버리고 정작 매칭된 부분이 화면 밖으로 밀려난다 — 공백 한 칸으로 합친다.
            val snippet = text.substring(snippetStart, snippetEnd).replace(Regex("\\s+"), " ").trim()
            results += SearchResult(idx, snippet)
            idx = text.indexOf(query, idx + query.length, ignoreCase = true)
        }
        lastSearchQuery = query
        lastSearchResults = results
        return results
    }

    // --- 설정 setter (SettingsController 구현) ---
    override fun setFontSizeSp(value: Float) = launchSetting { settingsRepository.updateFontSizeSp(value) }
    override fun setLineHeightMultiplier(value: Float) = launchSetting { settingsRepository.updateLineHeightMultiplier(value) }
    override fun setLetterSpacingSp(value: Float) = launchSetting { settingsRepository.updateLetterSpacingSp(value) }
    override fun setMarginHorizontalDp(value: Float) = launchSetting { settingsRepository.updateMarginHorizontalDp(value) }
    override fun setMarginTopDp(value: Float) = launchSetting { settingsRepository.updateMarginTopDp(value) }
    override fun setMarginBottomDp(value: Float) = launchSetting { settingsRepository.updateMarginBottomDp(value) }
    override fun setThemePreset(value: ThemePreset) = launchSetting { settingsRepository.updateThemePreset(value) }
    override fun setPageTurnMode(value: PageTurnMode) = launchSetting { settingsRepository.updatePageTurnMode(value) }
    override fun setBrightnessOverrideEnabled(value: Boolean) = launchSetting { settingsRepository.updateBrightnessOverrideEnabled(value) }
    override fun setBrightnessValue(value: Float) = launchSetting { settingsRepository.updateBrightnessValue(value) }
    override fun setOrientationLock(value: OrientationLock) = launchSetting { settingsRepository.updateOrientationLock(value) }
    override fun setLineBreakMode(value: LineBreakMode) = launchSetting { settingsRepository.updateLineBreakMode(value) }
    override fun setKeepScreenOnEnabled(value: Boolean) = launchSetting { settingsRepository.updateKeepScreenOnEnabled(value) }
    override fun setVolumeKeyPagingEnabled(value: Boolean) = launchSetting { settingsRepository.updateVolumeKeyPagingEnabled(value) }
    override fun setChapterJumpEnabled(value: Boolean) {
        lastChapterJumpOffset = null
        launchSetting { settingsRepository.updateChapterJumpEnabled(value) }
    }
    override fun setChapterJumpDivisions(value: Int) = launchSetting { settingsRepository.updateChapterJumpDivisions(value) }
    override fun setAutoPageTurnIntervalSeconds(value: Int) = launchSetting { settingsRepository.updateAutoPageTurnIntervalSeconds(value) }
    override fun selectFont(fontId: String) = launchSetting { settingsRepository.updateFontFamilyId(fontId) }
    override fun setTouchTurnMode(value: TouchTurnMode) = launchSetting { settingsRepository.updateTouchTurnMode(value) }
    override fun setSwipeTurnMode(value: SwipeTurnMode) = launchSetting { settingsRepository.updateSwipeTurnMode(value) }
    override fun setPageTransitionAnimation(value: PageTransitionAnimation) = launchSetting { settingsRepository.updatePageTransitionAnimation(value) }
    override fun setSupabaseSharedSecret(value: String) = launchSetting { settingsRepository.updateSupabaseSharedSecret(value) }

    // --- 챕터 인식 패턴 ---
    override fun toggleChapterPattern(id: String, enabled: Boolean) = launchSetting {
        val current = _uiState.value.settings.chapterPatternEnabledIds
        val updated = if (enabled) current + id else current - id
        settingsRepository.updateChapterPatternEnabledIds(updated)
    }

    /** 형식이 올바르지 않은 정규식이면 추가하지 않고 false를 반환한다. */
    override fun addCustomChapterPattern(pattern: String): Boolean {
        if (pattern.isBlank() || runCatching { Regex(pattern) }.isFailure) return false
        launchSetting {
            val current = _uiState.value.settings.chapterCustomPatterns
            settingsRepository.updateChapterCustomPatterns(current + pattern)
        }
        return true
    }

    override fun removeCustomChapterPattern(pattern: String) = launchSetting {
        val current = _uiState.value.settings.chapterCustomPatterns
        settingsRepository.updateChapterCustomPatterns(current - pattern)
    }

    override fun setAutoAdvanceMode(mode: AutoAdvanceMode) {
        if (mode == AutoAdvanceMode.TTS) {
            startTts()
        } else {
            launchSetting { settingsRepository.updateAutoAdvanceMode(mode) }
        }
    }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // --- 폰트 다운로드 ---
    override fun downloadFont(entry: FontCatalogEntry) = fontDownloadManager.download(entry)
    override fun isFontDownloaded(entry: FontCatalogEntry) = fontDownloadManager.isDownloaded(entry)

    // --- TTS ---
    fun startTts() {
        if (ttsController == null) {
            ttsController = TtsController(getApplication()) { utteranceId -> onTtsUtteranceDone(utteranceId) }
        }
        viewModelScope.launch { settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.TTS) }
        speakFromCurrentOffset()
    }

    fun stopTts() {
        ttsController?.stop()
        viewModelScope.launch { settingsRepository.updateAutoAdvanceMode(AutoAdvanceMode.OFF) }
    }

    private fun speakFromCurrentOffset() {
        val state = _uiState.value
        val start = state.currentOffset
        if (start >= state.fullText.length) {
            stopTts()
            return
        }
        val end = (start + ttsChunkChars).coerceAtMost(state.fullText.length)
        ttsPendingRange = start to end
        ttsController?.speak(start, state.fullText.substring(start, end))
    }

    private fun onTtsUtteranceDone(utteranceId: String) {
        viewModelScope.launch {
            val range = ttsPendingRange ?: return@launch
            if (_uiState.value.settings.autoAdvanceMode != AutoAdvanceMode.TTS) return@launch
            jumpToOffset(range.second)
            speakFromCurrentOffset()
        }
    }

    // --- 자동 넘김(타이머) ---
    private fun advance() {
        if (_uiState.value.settings.autoAdvanceMode != AutoAdvanceMode.TIMER) return
        next()
    }

    private var lastTimerMode: AutoAdvanceMode? = null
    private var lastTimerIntervalSeconds: Int? = null

    private fun handleAutoAdvanceModeChange(settings: ReaderSettings) {
        if (settings.autoAdvanceMode == AutoAdvanceMode.TIMER) {
            if (lastTimerMode != AutoAdvanceMode.TIMER || lastTimerIntervalSeconds != settings.autoPageTurnIntervalSeconds) {
                autoPageTurnController.start(settings.autoPageTurnIntervalSeconds)
            }
        } else {
            autoPageTurnController.stop()
        }
        lastTimerMode = settings.autoAdvanceMode
        lastTimerIntervalSeconds = settings.autoPageTurnIntervalSeconds
        if (settings.autoAdvanceMode != AutoAdvanceMode.TTS) {
            ttsController?.stop()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope는 onCleared 직후 취소되므로, 아직 저장 안 된 위치는 블로킹으로 즉시 반영한다.
        consumePendingOffset()?.let { offset -> runBlocking { persistPositionLocal(offset) } }
        // 뒤로가기 등으로 리더 화면을 완전히 벗어나는 시점 — 원격 체크포인트도 기다리지 않고 바로 반영.
        syncNowToRemote()
        pageComputeJob?.cancel()
        autoPageTurnController.stop()
        ttsController?.shutdown()
    }
}

class ReaderViewModelFactory(
    private val application: Application,
    private val bookId: Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val bookRepository = BookRepository(application, AppDatabase.getDatabase(application).bookDao())
        return ReaderViewModel(application, bookId, bookRepository) as T
    }
}

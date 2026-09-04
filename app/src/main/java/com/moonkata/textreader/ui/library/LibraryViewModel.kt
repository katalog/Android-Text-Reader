package com.moonkata.textreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.moonkata.textreader.BuildConfig
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
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.file.FolderBrowser
import com.moonkata.textreader.data.file.SafFolderBrowser
import com.moonkata.textreader.data.font.FontCatalogEntry
import com.moonkata.textreader.data.font.FontDownloadManager
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.data.sync.PcHostScanner
import com.moonkata.textreader.data.sync.PcSyncClient
import com.moonkata.textreader.data.sync.PcSyncFileManager
import com.moonkata.textreader.data.sync.PcSyncProgress
import com.moonkata.textreader.data.sync.PcSyncResult
import com.moonkata.textreader.data.sync.ReadingPositionSyncClient
import com.moonkata.textreader.data.sync.SupabaseConfig
import com.moonkata.textreader.data.sync.normalizeRelativePath
import com.moonkata.textreader.model.FolderEntry
import com.moonkata.textreader.model.FolderSortOption
import com.moonkata.textreader.ui.SettingsController
import com.moonkata.textreader.util.takePersistableReadPermission
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 폴더뷰에서 지금 들여다보고 있는 위치 한 칸 — 실제 SAF 폴더이거나 zip 아카이브 내부. */
sealed class BrowseLocation {
    abstract val name: String
    data class Folder(val uri: Uri, override val name: String) : BrowseLocation()
    data class Zip(val uri: Uri, override val name: String) : BrowseLocation()
}

private data class BrowseState(
    val rootUri: Uri? = null,
    val path: List<BrowseLocation> = emptyList(),
    val entries: List<FolderEntry> = emptyList(),
    val isLoading: Boolean = false,
    val sortOption: FolderSortOption = FolderSortOption.NAME_ASC,
)

/** PC 서버 "지금 동기화" 진행 상태 — `LibraryViewModel.pcSyncState`. */
data class PcSyncUiState(
    val isSyncing: Boolean = false,
    val progress: PcSyncProgress? = null,
    val result: PcSyncResult? = null,
    val errorMessage: String? = null,
)

data class LibraryUiState(
    val rootUri: Uri? = null,
    val path: List<BrowseLocation> = emptyList(),
    val entries: List<FolderEntry> = emptyList(),
    val isLoading: Boolean = false,
    val sortOption: FolderSortOption = FolderSortOption.NAME_ASC,
    val progressByStoredUri: Map<String, Float> = emptyMap(),
    val settings: ReaderSettings = ReaderSettings(),
)

/**
 * [bookRepository]/[settingsRepository]/[folderBrowser]는 생성자로 주입받는다 — 테스트가 실제 SAF
 * 권한이나 앱의 실제 Room DB 없이도 가짜 구현으로 갈아끼워서 폴더 탐색 시나리오를 검증할 수 있게 하기
 * 위함이다. 프로덕션 조립은 [LibraryViewModelFactory]가 담당한다.
 */
class LibraryViewModel(
    application: Application,
    private val bookRepository: BookRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val folderBrowser: FolderBrowser,
) : AndroidViewModel(application), SettingsController {

    private val fontDownloadManager = FontDownloadManager(application)

    private val _browseState = MutableStateFlow(BrowseState())

    private val _openBookEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openBookEvents: SharedFlow<Long> = _openBookEvents

    // 앱을 새로 켰을 때 딱 한 번만 "이어서 볼래요?"를 물어보기 위한 후보 — 사용자가 답하고 나면
    // 같은 프로세스가 살아있는 동안(라이브러리로 돌아왔다 다시 읽어도)은 다시 뜨지 않는다.
    private val _resumeCandidate = MutableStateFlow<BookEntity?>(null)
    val resumeCandidate: StateFlow<BookEntity?> = _resumeCandidate

    fun dismissResumePrompt() {
        _resumeCandidate.value = null
    }

    private val _pcSyncState = MutableStateFlow(PcSyncUiState())
    val pcSyncState: StateFlow<PcSyncUiState> = _pcSyncState

    fun dismissPcSyncResult() {
        _pcSyncState.update { it.copy(result = null, errorMessage = null) }
    }

    val uiState: StateFlow<LibraryUiState> =
        combine(_browseState, bookRepository.observeLibrary(), settingsRepository.settingsFlow) { browse, books, settings ->
            LibraryUiState(
                rootUri = browse.rootUri,
                path = browse.path,
                entries = sortEntries(browse.entries, browse.sortOption),
                isLoading = browse.isLoading,
                sortOption = browse.sortOption,
                progressByStoredUri = books.associate { it.documentUri to it.lastReadProgressPercent },
                settings = settings,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            _browseState.update { it.copy(sortOption = settings.librarySortOption) }
            if (settings.lastUsedSafTreeUri != null) openRoot(Uri.parse(settings.lastUsedSafTreeUri))
        }
        viewModelScope.launch {
            val mostRecent = bookRepository.observeLibrary().first().firstOrNull()
            // 파일이 삭제/이동됐거나 SAF 권한이 회수된 책은 후보에서 제외한다 — 그대로 후보로 올리면
            // "계속 보기"를 눌렀을 때 파일을 열 수 없어 앱이 죽는다.
            if (mostRecent?.lastOpenedAt != null && bookRepository.bookFileExists(mostRecent)) {
                _resumeCandidate.value = mostRecent
            }
        }
        if (BuildConfig.DEBUG) {
            viewModelScope.launch { seedDebugSyncDefaultsIfBlank() }
        }
    }

    /**
     * 실기기 동기화 테스트 편의용(디버그 빌드 전용) — PC 동기화 호스트/시크릿, VSCode 공유 시크릿을
     * 매번 QR 스캔이나 수동 입력 없이 미리 채워둔다. 값 자체는 local.properties의 DEBUG_PC_SYNC_HOST
     * 등을 통해 개발자 PC에서만 주입되고(app/build.gradle.kts 참고), 릴리스 빌드에서는 항상 빈
     * 문자열이라 이 함수가 아무 것도 하지 않는다. "검증됨" 상태까지는 안 채운다 — TOFU 지문 고정은
     * 실제로 한 번 연결 테스트를 거쳐야 의미가 있으므로, 필드만 채워서 "연결 테스트" 한 번으로 끝나게만
     * 한다. 이미 값이 있으면(사용자가 직접 설정했거나 이전에 이미 시드됨) 덮어쓰지 않는다.
     */
    private suspend fun seedDebugSyncDefaultsIfBlank() {
        val settings = settingsRepository.settingsFlow.first()
        if (settings.pcSyncHost.isBlank() && settings.pcSyncSecret.isBlank() &&
            BuildConfig.DEBUG_PC_SYNC_HOST.isNotBlank() && BuildConfig.DEBUG_PC_SYNC_SECRET.isNotBlank()
        ) {
            settingsRepository.updatePcSyncConnection(BuildConfig.DEBUG_PC_SYNC_HOST, BuildConfig.DEBUG_PC_SYNC_SECRET)
        }
        if (settings.supabaseSharedSecret.isBlank() && BuildConfig.DEBUG_SUPABASE_SHARED_SECRET.isNotBlank()) {
            settingsRepository.updateSupabaseSharedSecret(BuildConfig.DEBUG_SUPABASE_SHARED_SECRET)
        }
    }

    fun onRootFolderSelected(uri: Uri) {
        getApplication<Application>().takePersistableReadPermission(uri)
        viewModelScope.launch { settingsRepository.updateLastUsedSafTreeUri(uri.toString()) }
        openRoot(uri)
    }

    private fun openRoot(uri: Uri) {
        val name = folderBrowser.rootDisplayName(uri)
        _browseState.update { it.copy(rootUri = uri, path = listOf(BrowseLocation.Folder(uri, name))) }
        loadCurrent()
    }

    fun navigateInto(entry: FolderEntry) {
        when (entry) {
            is FolderEntry.Folder -> {
                _browseState.update { it.copy(path = it.path + BrowseLocation.Folder(entry.uri, entry.name)) }
                loadCurrent()
            }
            is FolderEntry.ZipArchive -> {
                _browseState.update { it.copy(path = it.path + BrowseLocation.Zip(entry.uri, entry.name)) }
                loadCurrent()
            }
            is FolderEntry.TextFile -> openTextFile(entry)
        }
    }

    /** true를 반환하면 상위 폴더로 이동함(호출자가 뒤로가기를 소비해야 함), false면 이미 최상위. */
    fun navigateUp(): Boolean {
        val path = _browseState.value.path
        if (path.size <= 1) return false
        _browseState.update { it.copy(path = path.dropLast(1)) }
        loadCurrent()
        return true
    }

    fun navigateToBreadcrumb(index: Int) {
        val path = _browseState.value.path
        if (index < 0 || index >= path.size - 1) return
        _browseState.update { it.copy(path = path.subList(0, index + 1)) }
        loadCurrent()
    }

    fun setSortOption(option: FolderSortOption) {
        _browseState.update { it.copy(sortOption = option) }
        viewModelScope.launch { settingsRepository.updateLibrarySortOption(option) }
    }

    private fun loadCurrent() {
        val location = _browseState.value.path.lastOrNull() ?: return
        viewModelScope.launch {
            _browseState.update { it.copy(isLoading = true) }
            val entries = when (location) {
                is BrowseLocation.Folder -> folderBrowser.listFolder(location.uri)
                is BrowseLocation.Zip -> folderBrowser.listZipEntries(location.uri)
            }
            _browseState.update { it.copy(entries = entries, isLoading = false) }
        }
    }

    private fun openTextFile(entry: FolderEntry.TextFile) {
        viewModelScope.launch {
            // zip 안 파일은 VSCode에서 직접 열리는 경로가 아니라 동기화 매칭 대상이 아님(§3) — 빈 값으로 둔다.
            val relativePath = if (entry.source is BookSource.PlainTxt) {
                val folderNames = _browseState.value.path.drop(1).map { it.name }
                normalizeRelativePath(folderNames + entry.name)
            } else {
                ""
            }
            val id = bookRepository.findOrCreateBook(entry.source, entry.name, entry.sizeBytes, relativePath)
            _openBookEvents.tryEmit(id)
        }
    }

    // --- PC 트레이 서버 파일 동기화 (.docs/PC_SYNC_SERVER_PLAN.md §3) ---

    /** 연결 테스트 없이 입력값만 임시 저장 — 시트가 닫힐 때 커밋하는 용도(Supabase 공유 시크릿과 동일 패턴). */
    fun updatePcSyncConnectionDraft(host: String, secret: String) {
        viewModelScope.launch { settingsRepository.updatePcSyncConnection(host, secret, verified = false) }
    }

    private var lastPcSyncTestError: String? = null

    /** [PcSyncSheet]가 실패 문구에 그대로 보여준다 — VSCode 동기화 쪽과 같은 이유로 추가
     * (.docs/SYNC_MULTIUSER_PLAN.md 참고). */
    fun lastPcSyncTestError(): String? = lastPcSyncTestError

    /** 설정 화면 "연결 테스트" 버튼 — 성공하면 입력값을 검증 상태와 함께 커밋한다. 이때 받은 PC
     * 인증서 지문도 같이 저장(TOFU) — 클라이언트를 pinnedFingerprint 없이(=아직 아무 인증서나 믿는
     * 상태로) 만들어서, 실제로 받은 인증서를 그 자리에서 "이 PC"로 등록하는 셈이다. */
    suspend fun testPcSyncConnection(host: String, secret: String): Boolean {
        if (host.isBlank() || secret.isBlank()) {
            lastPcSyncTestError = "주소 또는 시크릿이 비어있음"
            return false
        }
        val client = PcSyncClient(host, secret)
        val success = client.testConnection()
        lastPcSyncTestError = if (success) null else client.lastTestConnectionError
        if (success) {
            settingsRepository.updatePcSyncConnection(host, secret, verified = true, fingerprint = client.lastSeenFingerprint)
        }
        return success
    }

    /** "PC 찾기" 버튼 — 로컬 서브넷에서 PC 트레이 서버를 찾아 IP 목록을 돌려준다. */
    suspend fun scanForPcSyncHosts(): List<String> = PcHostScanner(getApplication()).scanLocalSubnet()

    /**
     * QR 페어링(.docs/SYNC_MULTIUSER_PLAN.md 스테이지 6) 전용 — PC 트레이 서버의 `/pair` QR에는
     * 호스트/시크릿뿐 아니라 인증서 지문도 이미 실려있다. 그래서 [testPcSyncConnection]처럼 먼저
     * lenient TLS로 접속해 지문을 "그 자리에서 처음 본 값"으로 등록하는 TOFU 단계를 거칠 필요 없이,
     * 처음부터 그 지문으로 pinned TLS 연결을 시도한다 — 성공하면 "QR이 알려준 서버가 실제로 그
     * 인증서를 갖고 있다"까지 확인된 것이라 TOFU보다 신뢰 근거가 더 강하다.
     */
    suspend fun testPcSyncConnectionWithFingerprint(host: String, secret: String, fingerprint: String): Boolean {
        if (host.isBlank() || secret.isBlank() || fingerprint.isBlank()) {
            lastPcSyncTestError = "QR 페이로드에 빈 값이 있음"
            return false
        }
        val client = PcSyncClient(host, secret, fingerprint)
        val success = client.testConnection()
        lastPcSyncTestError = if (success) null else client.lastTestConnectionError
        if (success) {
            settingsRepository.updatePcSyncConnection(host, secret, verified = true, fingerprint = fingerprint)
        }
        return success
    }

    /**
     * "지금 동기화" 버튼 — 연결 테스트를 통과한 설정으로만 동작한다(Supabase 시크릿과 동일하게 "테스트
     * 성공 시점의 값과 지금 설정값이 정확히 같을 때"만 검증된 것으로 침). 진행 상태는 [pcSyncState]로
     * 노출되고, 끝나면 [PcSyncUiState.result] 또는 [PcSyncUiState.errorMessage]가 채워진다.
     */
    fun syncFromPc() {
        val rootUri = _browseState.value.rootUri
        if (rootUri == null) {
            _pcSyncState.update { it.copy(errorMessage = "먼저 라이브러리 폴더를 선택하세요") }
            return
        }
        viewModelScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val verified = settings.pcSyncHost.isNotBlank() &&
                settings.pcSyncHost == settings.pcSyncVerifiedHost &&
                settings.pcSyncSecret == settings.pcSyncVerifiedSecret
            if (!verified) {
                _pcSyncState.update { it.copy(errorMessage = "먼저 연결 테스트를 통과해야 합니다") }
                return@launch
            }
            _pcSyncState.update { PcSyncUiState(isSyncing = true) }
            // 평소 동기화는 연결 테스트 때 저장해둔 지문으로 고정 검증한다(pinnedFingerprint 지정) —
            // 그 지문과 다른 인증서를 내미는 서버는 거부된다.
            val client = PcSyncClient(settings.pcSyncHost, settings.pcSyncSecret, settings.pcSyncPinnedFingerprint)
            val manager = PcSyncFileManager(getApplication(), client)
            // 지난번 동기화가 언제 끝났는지(이 기기 시계 기준) 넘겨서, 크기는 같지만 그 이후 PC에서
            // 내용이 바뀐 파일도 다시 받게 한다(computeSyncDelta 참고). 0이면 한 번도 성공한 적 없다는
            // 뜻이라 null로 넘겨 이 보정을 건너뛴다.
            val sinceMillis = settings.pcSyncLastCompletedAtMillis.takeIf { it > 0 }
            val syncStartedAt = System.currentTimeMillis()
            val result = manager.sync(rootUri, sinceMillis = sinceMillis) { progress ->
                _pcSyncState.update { it.copy(progress = progress) }
            }
            _pcSyncState.update {
                if (result != null) {
                    it.copy(isSyncing = false, progress = null, result = result)
                } else {
                    it.copy(isSyncing = false, progress = null, errorMessage = "PC에 연결할 수 없습니다")
                }
            }
            // 동기화가 지금 보고 있는 폴더 안의 파일을 바꿨을 수 있는데, 폴더뷰 목록은 처음 들어왔을 때
            // 한 번만 읽어온 상태라 그대로 두면 새로 받은 파일이 화면엔 안 보인다(실기기 검증 중 확인) —
            // 성공하면 지금 위치를 다시 읽어온다.
            if (result != null) {
                loadCurrent()
                // 동기화 "끝난" 시각이 아니라 "시작한" 시각을 기준선으로 남긴다 — 동기화가 도는 동안(수
                // 초~수십 초) PC에서 또 파일을 바꾸면 그 변경은 원격 mtime이 시작 시각보다 늦을 수 있어
                // "끝난 시각"을 쓰면 다음 번에 놓친다.
                settingsRepository.updatePcSyncLastCompletedAt(syncStartedAt)
            }
        }
    }

    // --- SettingsController 구현 — 서재 화면에서도 QuickSettingsSheet를 그대로 재사용하기 위함.
    // 열린 책이 없으니 값을 저장하는 것 이상의 부가 동작(ReaderViewModel의 TTS 즉시 시작, 방문 이력
    // 초기화 등)은 하지 않는다 — 그건 나중에 실제로 책을 열었을 때 ReaderViewModel이 저장된 값을 읽어
    // 알아서 적용한다. ---
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
    override fun setChapterJumpEnabled(value: Boolean) = launchSetting { settingsRepository.updateChapterJumpEnabled(value) }
    override fun setChapterJumpDivisions(value: Int) = launchSetting { settingsRepository.updateChapterJumpDivisions(value) }
    override fun setAutoPageTurnIntervalSeconds(value: Int) = launchSetting { settingsRepository.updateAutoPageTurnIntervalSeconds(value) }
    override fun selectFont(fontId: String) = launchSetting { settingsRepository.updateFontFamilyId(fontId) }
    override fun setTouchTurnMode(value: TouchTurnMode) = launchSetting { settingsRepository.updateTouchTurnMode(value) }
    override fun setSwipeTurnMode(value: SwipeTurnMode) = launchSetting { settingsRepository.updateSwipeTurnMode(value) }
    override fun setPageTransitionAnimation(value: PageTransitionAnimation) = launchSetting { settingsRepository.updatePageTransitionAnimation(value) }
    override fun setSupabaseSharedSecret(value: String) = launchSetting { settingsRepository.updateSupabaseSharedSecret(value) }
    override fun setAutoAdvanceMode(mode: AutoAdvanceMode) = launchSetting { settingsRepository.updateAutoAdvanceMode(mode) }

    override fun toggleChapterPattern(id: String, enabled: Boolean) = launchSetting {
        val current = uiState.value.settings.chapterPatternEnabledIds
        val updated = if (enabled) current + id else current - id
        settingsRepository.updateChapterPatternEnabledIds(updated)
    }

    override fun addCustomChapterPattern(pattern: String): Boolean {
        if (pattern.isBlank() || runCatching { Regex(pattern) }.isFailure) return false
        launchSetting {
            val current = uiState.value.settings.chapterCustomPatterns
            settingsRepository.updateChapterCustomPatterns(current + pattern)
        }
        return true
    }

    override fun removeCustomChapterPattern(pattern: String) = launchSetting {
        val current = uiState.value.settings.chapterCustomPatterns
        settingsRepository.updateChapterCustomPatterns(current - pattern)
    }

    override fun downloadFont(entry: FontCatalogEntry) = fontDownloadManager.download(entry)
    override fun isFontDownloaded(entry: FontCatalogEntry) = fontDownloadManager.isDownloaded(entry)

    private var lastSupabaseTestError: String? = null

    override suspend fun testSupabaseConnection(secret: String): Boolean {
        if (secret.isBlank()) {
            lastSupabaseTestError = "시크릿이 비어있음"
            return false
        }
        val client = ReadingPositionSyncClient(SupabaseConfig.URL, SupabaseConfig.PUBLISHABLE_KEY, secret)
        val success = client.testConnection()
        lastSupabaseTestError = if (success) null else client.lastTestConnectionError
        if (success) settingsRepository.updateSupabaseSharedSecret(secret, verifiedSecret = secret)
        return success
    }

    override fun lastSupabaseTestError(): String? = lastSupabaseTestError

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun sortEntries(entries: List<FolderEntry>, option: FolderSortOption): List<FolderEntry> {
        val folders = entries.filterIsInstance<FolderEntry.Folder>().let { folders ->
            if (option == FolderSortOption.NAME_DESC) folders.sortedByDescending { it.name.lowercase() }
            else folders.sortedBy { it.name.lowercase() }
        }
        val files = entries.filterNot { it is FolderEntry.Folder }
        val sortedFiles = when (option) {
            FolderSortOption.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            FolderSortOption.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
            FolderSortOption.DATE_DESC -> files.sortedByDescending { lastModifiedOf(it) }
            FolderSortOption.DATE_ASC -> files.sortedBy { lastModifiedOf(it) }
            FolderSortOption.SIZE_DESC -> files.sortedByDescending { sizeOf(it) }
            FolderSortOption.SIZE_ASC -> files.sortedBy { sizeOf(it) }
        }
        return folders + sortedFiles
    }

    private fun lastModifiedOf(entry: FolderEntry): Long = when (entry) {
        is FolderEntry.TextFile -> entry.lastModified
        is FolderEntry.ZipArchive -> entry.lastModified
        is FolderEntry.Folder -> 0L
    }

    private fun sizeOf(entry: FolderEntry): Long = when (entry) {
        is FolderEntry.TextFile -> entry.sizeBytes
        is FolderEntry.ZipArchive -> entry.sizeBytes
        is FolderEntry.Folder -> 0L
    }
}

class LibraryViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getDatabase(application)
        return LibraryViewModel(
            application = application,
            bookRepository = BookRepository(application, db.bookDao()),
            settingsRepository = ReaderSettingsRepository(application),
            folderBrowser = SafFolderBrowser(application),
        ) as T
    }
}

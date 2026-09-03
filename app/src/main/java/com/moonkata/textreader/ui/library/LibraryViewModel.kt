package com.moonkata.textreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.db.BookEntity
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.file.FolderBrowser
import com.moonkata.textreader.data.file.SafFolderBrowser
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.data.sync.PcHostScanner
import com.moonkata.textreader.data.sync.PcSyncClient
import com.moonkata.textreader.data.sync.PcSyncFileManager
import com.moonkata.textreader.data.sync.PcSyncProgress
import com.moonkata.textreader.data.sync.PcSyncResult
import com.moonkata.textreader.data.sync.normalizeRelativePath
import com.moonkata.textreader.model.FolderEntry
import com.moonkata.textreader.model.FolderSortOption
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
) : AndroidViewModel(application) {

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

    /** 설정 화면 "연결 테스트" 버튼 — 성공하면 입력값을 검증 상태와 함께 커밋한다. 이때 받은 PC
     * 인증서 지문도 같이 저장(TOFU) — 클라이언트를 pinnedFingerprint 없이(=아직 아무 인증서나 믿는
     * 상태로) 만들어서, 실제로 받은 인증서를 그 자리에서 "이 PC"로 등록하는 셈이다. */
    suspend fun testPcSyncConnection(host: String, secret: String): Boolean {
        if (host.isBlank() || secret.isBlank()) return false
        val client = PcSyncClient(host, secret)
        val success = client.testConnection()
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
        if (host.isBlank() || secret.isBlank() || fingerprint.isBlank()) return false
        val client = PcSyncClient(host, secret, fingerprint)
        val success = client.testConnection()
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
            val result = manager.sync(rootUri) { progress ->
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
            if (result != null) loadCurrent()
        }
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

package com.moonkata.textreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.db.BookEntity
import com.moonkata.textreader.data.file.FolderBrowser
import com.moonkata.textreader.data.file.SafFolderBrowser
import com.moonkata.textreader.data.repository.BookRepository
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

data class LibraryUiState(
    val rootUri: Uri? = null,
    val path: List<BrowseLocation> = emptyList(),
    val entries: List<FolderEntry> = emptyList(),
    val isLoading: Boolean = false,
    val sortOption: FolderSortOption = FolderSortOption.NAME_ASC,
    val progressByStoredUri: Map<String, Float> = emptyMap(),
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

    val uiState: StateFlow<LibraryUiState> = combine(_browseState, bookRepository.observeLibrary()) { browse, books ->
        LibraryUiState(
            rootUri = browse.rootUri,
            path = browse.path,
            entries = sortEntries(browse.entries, browse.sortOption),
            isLoading = browse.isLoading,
            sortOption = browse.sortOption,
            progressByStoredUri = books.associate { it.documentUri to it.lastReadProgressPercent },
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
            val id = bookRepository.findOrCreateBook(entry.source, entry.name, entry.sizeBytes)
            _openBookEvents.tryEmit(id)
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

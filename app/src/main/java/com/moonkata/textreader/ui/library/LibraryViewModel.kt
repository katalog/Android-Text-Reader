package com.moonkata.textreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.moonkata.textreader.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moonkata.textreader.R
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

/** One step of where the folder view is currently looking — either a real SAF folder or inside a zip archive. */
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

/** The PC server "sync now" progress state — `LibraryViewModel.pcSyncState`. */
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
 * [bookRepository]/[settingsRepository]/[folderBrowser] are injected via the constructor — so tests
 * can swap in fake implementations to verify folder-browsing scenarios without real SAF permissions
 * or the app's real Room DB. Production wiring is handled by [LibraryViewModelFactory].
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

    // Candidate for the "resume reading?" prompt, asked exactly once per fresh app launch — once the
    // user answers, it won't show again for as long as this process stays alive (even after
    // navigating back to the library and re-reading).
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
            // Exclude a book whose file was deleted/moved or whose SAF permission was revoked from
            // the candidate — leaving it as a candidate would crash the app when the file can't be
            // opened after tapping "Continue".
            if (mostRecent?.lastOpenedAt != null && bookRepository.bookFileExists(mostRecent)) {
                _resumeCandidate.value = mostRecent
            }
        }
        if (BuildConfig.DEBUG) {
            viewModelScope.launch { seedDebugSyncDefaultsIfBlank() }
        }
    }

    /**
     * Real-device sync testing convenience (debug builds only) — pre-fills the PC sync host/secret
     * and the VSCode shared secret without a QR scan or manual entry each time. The values themselves
     * are injected only on the developer's PC via local.properties' DEBUG_PC_SYNC_HOST etc. (see
     * app/build.gradle.kts), and are always an empty string in release builds, so this function does
     * nothing there. Doesn't fill in the "verified" state — TOFU fingerprint pinning is only
     * meaningful after an actual connection test, so this just fills the fields, leaving one
     * "Test connection" tap to finish the job. Doesn't overwrite existing values (set by the user, or
     * already seeded earlier).
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

    /** Returns true if it moved to the parent folder (the caller should consume the back press), false if already at the top. */
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
            // A file inside a zip has no direct path VSCode could open, so it's not a sync-matching target (§3) — leave it blank.
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

    // --- PC tray server file sync (.docs/PC_SYNC_SERVER_PLAN.md §3) ---

    /** Stashes the input values without a connection test — committed when the sheet closes (same pattern as the Supabase shared secret). */
    fun updatePcSyncConnectionDraft(host: String, secret: String) {
        viewModelScope.launch { settingsRepository.updatePcSyncConnection(host, secret, verified = false) }
    }

    private var lastPcSyncTestError: String? = null

    /** [PcSyncSheet] shows this verbatim in its failure message — added for the same reason as the
     * VSCode sync side (see .docs/SYNC_MULTIUSER_PLAN.md). */
    fun lastPcSyncTestError(): String? = lastPcSyncTestError

    /** The settings screen's "Test connection" button — on success, commits the input values together
     * with verified state. Also saves the PC certificate fingerprint received at that point (TOFU) —
     * the client is built without a pinnedFingerprint (i.e. still trusting any certificate), so this
     * amounts to registering whatever certificate is actually received, right there, as "this PC". */
    suspend fun testPcSyncConnection(host: String, secret: String): Boolean {
        if (host.isBlank() || secret.isBlank()) {
            lastPcSyncTestError = getApplication<Application>().getString(R.string.library_pc_sync_secret_empty)
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

    /** The "Find PC" button — looks for the PC tray server on the local subnet and returns a list of IPs. */
    suspend fun scanForPcSyncHosts(): List<String> = PcHostScanner(getApplication()).scanLocalSubnet()

    /**
     * For QR pairing only (.docs/SYNC_MULTIUSER_PLAN.md stage 6) — the PC tray server's `/pair` QR
     * already carries the certificate fingerprint, not just the host/secret. So unlike
     * [testPcSyncConnection], there's no need for a TOFU step that first connects over lenient TLS to
     * register the fingerprint as "the first value seen right now" — it attempts a pinned TLS
     * connection with that fingerprint from the start. Success then means "the server the QR pointed
     * to actually holds that certificate," which is a stronger basis of trust than TOFU.
     */
    suspend fun testPcSyncConnectionWithFingerprint(host: String, secret: String, fingerprint: String): Boolean {
        if (host.isBlank() || secret.isBlank() || fingerprint.isBlank()) {
            lastPcSyncTestError = getApplication<Application>().getString(R.string.library_pc_sync_qr_payload_empty)
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
     * The "Sync now" button — only works with settings that passed the connection test (verified the
     * same way as the Supabase secret: only counts as verified when the current settings value
     * exactly matches the value at the moment the test last succeeded). Progress is exposed via
     * [pcSyncState], and [PcSyncUiState.result] or [PcSyncUiState.errorMessage] gets filled in once
     * it finishes.
     */
    fun syncFromPc() {
        val rootUri = _browseState.value.rootUri
        if (rootUri == null) {
            _pcSyncState.update { it.copy(errorMessage = getApplication<Application>().getString(R.string.library_pc_sync_select_folder_first)) }
            return
        }
        viewModelScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val verified = settings.pcSyncHost.isNotBlank() &&
                settings.pcSyncHost == settings.pcSyncVerifiedHost &&
                settings.pcSyncSecret == settings.pcSyncVerifiedSecret
            if (!verified) {
                _pcSyncState.update { it.copy(errorMessage = getApplication<Application>().getString(R.string.library_pc_sync_test_connection_first)) }
                return@launch
            }
            _pcSyncState.update { PcSyncUiState(isSyncing = true) }
            // Regular syncs are pinned to the fingerprint saved during the connection test
            // (pinnedFingerprint set) — a server presenting a different certificate is rejected.
            val client = PcSyncClient(settings.pcSyncHost, settings.pcSyncSecret, settings.pcSyncPinnedFingerprint)
            val manager = PcSyncFileManager(getApplication(), client)
            // Pass along when the last sync finished (this device's clock) so a file whose size is
            // unchanged but that was modified on the PC afterward still gets re-downloaded (see
            // computeSyncDelta). 0 means never succeeded yet, so pass null to skip this correction.
            val sinceMillis = settings.pcSyncLastCompletedAtMillis.takeIf { it > 0 }
            val syncStartedAt = System.currentTimeMillis()
            val result = manager.sync(rootUri, sinceMillis = sinceMillis) { progress ->
                _pcSyncState.update { it.copy(progress = progress) }
            }
            _pcSyncState.update {
                if (result != null) {
                    it.copy(isSyncing = false, progress = null, result = result)
                } else {
                    it.copy(isSyncing = false, progress = null, errorMessage = getApplication<Application>().getString(R.string.library_pc_sync_cannot_connect))
                }
            }
            // The sync may have changed files inside the folder currently being viewed, but the
            // folder-view listing was only read once when it was first entered — leaving it as-is
            // means newly received files wouldn't show up on screen (confirmed during real-device
            // testing) — re-read the current location on success.
            if (result != null) {
                loadCurrent()
                // Records the sync's *start* time as the baseline, not when it finished — if the PC
                // changes another file while the sync is running (a few seconds to tens of seconds),
                // that change's remote mtime could be earlier than the finish time, and using the
                // finish time would miss it next time.
                settingsRepository.updatePcSyncLastCompletedAt(syncStartedAt)
            }
        }
    }

    // --- SettingsController implementation — so QuickSettingsSheet can be reused as-is from the
    // library screen too. There's no open book here, so this does nothing beyond persisting values
    // (no side effects like ReaderViewModel's immediate TTS start or resetting navigation history) —
    // ReaderViewModel reads the saved values and applies them itself once a book is actually opened
    // later. ---
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
            lastSupabaseTestError = getApplication<Application>().getString(R.string.library_supabase_secret_empty)
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

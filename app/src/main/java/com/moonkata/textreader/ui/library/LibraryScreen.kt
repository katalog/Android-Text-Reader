package com.moonkata.textreader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moonkata.textreader.R
import com.moonkata.textreader.model.FolderEntry
import com.moonkata.textreader.model.FolderSortOption
import com.moonkata.textreader.ui.reader.QuickSettingsSheet
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (Long) -> Unit,
    viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(LocalContext.current.applicationContext as Application),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val resumeCandidate by viewModel.resumeCandidate.collectAsState()
    val pickFolder = rememberFolderPickerLauncher(onFolderSelected = viewModel::onRootFolderSelected)
    var showSortMenu by remember { mutableStateOf(false) }
    var showPcSync by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.openBookEvents.collect { bookId -> onOpenBook(bookId) }
    }

    resumeCandidate?.let { book ->
        ResumeReadingDialog(
            displayName = book.displayName,
            onConfirm = {
                viewModel.dismissResumePrompt()
                onOpenBook(book.id)
            },
            onDismiss = viewModel::dismissResumePrompt,
        )
    }

    BackHandler(enabled = uiState.path.size > 1) { viewModel.navigateUp() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(uiState.path.lastOrNull()?.name ?: stringResource(R.string.library_default_title)) },
                    navigationIcon = {
                        if (uiState.path.size > 1) {
                            IconButton(onClick = { viewModel.navigateUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.library_up_folder_desc))
                            }
                        }
                    },
                    actions = {
                        if (uiState.rootUri != null) {
                            IconButton(onClick = { showPcSync = true }) {
                                Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.library_pc_sync_desc))
                            }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.library_sort_desc))
                                }
                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                    FolderSortOption.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(option.labelRes)) },
                                            onClick = { viewModel.setSortOption(option); showSortMenu = false },
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.library_settings_desc))
                            }
                        }
                    },
                )
                if (uiState.path.size > 1) {
                    Breadcrumbs(path = uiState.path, onClick = viewModel::navigateToBreadcrumb)
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = pickFolder,
                icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                text = { Text(if (uiState.rootUri == null) stringResource(R.string.library_add_folder) else stringResource(R.string.library_change_folder)) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (uiState.isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            if (uiState.rootUri == null) {
                Text(
                    stringResource(
                        if (uiState.folderAccessLost) R.string.library_folder_access_lost
                        else R.string.library_no_folder_added
                    ),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else if (uiState.entries.isEmpty() && !uiState.isLoading) {
                Text(
                    stringResource(R.string.library_no_txt_zip_files),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(uiState.entries, key = { entryKey(it) }) { entry ->
                        EntryRow(
                            entry = entry,
                            progress = (entry as? FolderEntry.TextFile)?.let { uiState.progressByStoredUri[it.source.toStoredString()] },
                            onClick = { viewModel.navigateInto(entry) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showPcSync) {
        PcSyncSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = { showPcSync = false })
    }
    if (showSettings) {
        // There's no open book on the library screen, so a ReaderViewModel can't be created —
        // LibraryViewModel implements SettingsController so the same settings sheet can be reused
        // here too (font/margins/theme/VSCode sync etc. are app-wide settings unrelated to any
        // particular book, so they should be changeable without opening one first — added from
        // real-usage feedback).
        QuickSettingsSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = { showSettings = false })
    }
}

/** Pure UI with no Room/ViewModel dependency, so it can be tested directly without the data layer. */
@Composable
fun ResumeReadingDialog(displayName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_resume_reading_title)) },
        text = { Text(stringResource(R.string.library_resume_reading_message, displayName)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.library_resume_reading_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_resume_reading_dismiss)) } },
    )
}

@Composable
private fun Breadcrumbs(path: List<BrowseLocation>, onClick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        path.forEachIndexed { index, location ->
            if (index > 0) Text(" › ", style = MaterialTheme.typography.bodySmall)
            val isLast = index == path.lastIndex
            Text(
                location.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = if (isLast) Modifier else Modifier.clickable { onClick(index) },
            )
        }
    }
}

@Composable
private fun EntryRow(entry: FolderEntry, progress: Float?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (entry) {
            is FolderEntry.Folder -> Icons.Default.Folder
            is FolderEntry.ZipArchive -> Icons.Default.FolderZip
            is FolderEntry.TextFile -> Icons.Default.Description
        }
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = when (entry) {
                is FolderEntry.Folder -> null
                is FolderEntry.ZipArchive -> formatMeta(entry.sizeBytes, entry.lastModified)
                is FolderEntry.TextFile -> formatMeta(entry.sizeBytes, entry.lastModified)
            }
            if (meta != null) {
                Spacer(Modifier.height(4.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall)
            }
            if (entry is FolderEntry.TextFile) {
                Spacer(Modifier.height(4.dp))
                val readStatus = if (progress != null && progress > 0f) {
                    stringResource(R.string.library_percent_read, progress * 100)
                } else {
                    stringResource(R.string.library_unread)
                }
                Text(
                    readStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress != null && progress > 0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatMeta(sizeBytes: Long, lastModified: Long): String {
    val size = formatSize(sizeBytes)
    return if (lastModified > 0) "$size · ${dateFormat.format(lastModified)}" else size
}

private fun entryKey(entry: FolderEntry): String = when (entry) {
    is FolderEntry.Folder -> entry.uri.toString()
    is FolderEntry.ZipArchive -> entry.uri.toString()
    is FolderEntry.TextFile -> entry.source.toStoredString()
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024f * 1024f))
    bytes >= 1024 -> "%.0fKB".format(bytes / 1024f)
    else -> "${bytes}B"
}

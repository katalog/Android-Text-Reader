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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moonkata.textreader.model.FolderEntry
import com.moonkata.textreader.model.FolderSortOption
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
                    title = { Text(uiState.path.lastOrNull()?.name ?: "내 서재") },
                    navigationIcon = {
                        if (uiState.path.size > 1) {
                            IconButton(onClick = { viewModel.navigateUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "상위 폴더")
                            }
                        }
                    },
                    actions = {
                        if (uiState.rootUri != null) {
                            IconButton(onClick = { showPcSync = true }) {
                                Icon(Icons.Default.Sync, contentDescription = "PC 파일 동기화 설정")
                            }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "정렬")
                                }
                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                    FolderSortOption.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = { viewModel.setSortOption(option); showSortMenu = false },
                                        )
                                    }
                                }
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
                text = { Text(if (uiState.rootUri == null) "폴더 추가" else "폴더 변경") },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (uiState.isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            if (uiState.rootUri == null) {
                Text(
                    "폴더를 추가해서 txt 소설을 불러오세요",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else if (uiState.entries.isEmpty() && !uiState.isLoading) {
                Text(
                    "이 폴더에는 txt/zip 파일이 없습니다",
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
}

/** Room/ViewModel과 무관한 순수 UI라 데이터 계층 없이 바로 테스트할 수 있다. */
@Composable
fun ResumeReadingDialog(displayName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이어서 읽기") },
        text = { Text("\"$displayName\" 계속 보시겠어요?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("계속 보기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("괜찮아요") } },
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
                    "%.3f%% 읽음".format(progress * 100)
                } else {
                    "안읽음"
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

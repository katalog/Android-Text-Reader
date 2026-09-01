package com.moonkata.textreader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.font.FontCatalog
import com.moonkata.textreader.data.font.FontCatalogEntry
import com.moonkata.textreader.data.font.FontDownloadState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontPickerSheet(viewModel: ReaderViewModel, settings: ReaderSettings, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("폰트", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FontRow(
                displayName = "시스템 기본",
                selected = settings.fontFamilyId == FontCatalog.SYSTEM_DEFAULT_ID,
                downloaded = true,
                downloading = false,
                onSelect = { viewModel.selectFont(FontCatalog.SYSTEM_DEFAULT_ID) },
                onDownload = null,
            )
            FontCatalog.entries.forEach { entry ->
                FontEntryRow(viewModel = viewModel, settings = settings, entry = entry)
            }
        }
    }
}

@Composable
private fun FontEntryRow(viewModel: ReaderViewModel, settings: ReaderSettings, entry: FontCatalogEntry) {
    var downloadState by remember(entry.id) {
        mutableStateOf<FontDownloadState>(
            if (viewModel.isFontDownloaded(entry)) FontDownloadState.Downloaded else FontDownloadState.NotDownloaded,
        )
    }
    val coroutineScope = rememberCoroutineScope()

    FontRow(
        displayName = "${entry.displayName} (${entry.license})",
        selected = settings.fontFamilyId == entry.id,
        downloaded = downloadState is FontDownloadState.Downloaded,
        downloading = downloadState is FontDownloadState.Downloading,
        onSelect = { if (downloadState is FontDownloadState.Downloaded) viewModel.selectFont(entry.id) },
        onDownload = {
            coroutineScope.launch {
                viewModel.downloadFont(entry).collect { state -> downloadState = state }
            }
        },
    )
}

@Composable
private fun FontRow(
    displayName: String,
    selected: Boolean,
    downloaded: Boolean,
    downloading: Boolean,
    onSelect: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = downloaded) { onSelect() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = { if (downloaded) onSelect() }, enabled = downloaded)
        Text(displayName, modifier = Modifier.weight(1f))
        when {
            downloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            !downloaded && onDownload != null -> {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "다운로드")
                }
            }
            else -> {}
        }
    }
}

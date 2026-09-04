package com.moonkata.textreader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.R
import com.moonkata.textreader.ui.theme.ReaderColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String,
    readerColors: ReaderColors,
    chapterJumpEnabled: Boolean,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onToggleChapterJump: () -> Unit,
) {
    Surface(color = readerColors.background, contentColor = readerColors.text, shadowElevation = 4.dp) {
        Column {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.reader_back_desc))
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.reader_settings_desc)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = readerColors.background,
                    titleContentColor = readerColors.text,
                    navigationIconContentColor = readerColors.text,
                    actionIconContentColor = readerColors.text,
                ),
            )
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToc) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.reader_toc_desc)) }
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.reader_search_desc)) }
                Spacer(Modifier.width(4.dp))
                ChapterJumpToggle(enabled = chapterJumpEnabled, onToggle = onToggleChapterJump)
            }
        }
    }
}

/** Chapter-jump on/off toggle — a text label next to the icon keeps it from reading as a "next" button, and a solid fill when on makes the state unambiguous. */
@Composable
private fun ChapterJumpToggle(enabled: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(50),
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.height(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (enabled) stringResource(R.string.reader_chapter_jump_on) else stringResource(R.string.reader_chapter_jump),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

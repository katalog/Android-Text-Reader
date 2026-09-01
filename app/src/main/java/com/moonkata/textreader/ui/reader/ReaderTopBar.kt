package com.moonkata.textreader.ui.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.ui.theme.ReaderColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String,
    readerColors: ReaderColors,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = readerColors.background, contentColor = readerColors.text, shadowElevation = 4.dp) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            },
            actions = {
                IconButton(onClick = onToc) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "목차") }
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "검색") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "설정") }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = readerColors.background,
                titleContentColor = readerColors.text,
                navigationIconContentColor = readerColors.text,
                actionIconContentColor = readerColors.text,
            ),
        )
    }
}

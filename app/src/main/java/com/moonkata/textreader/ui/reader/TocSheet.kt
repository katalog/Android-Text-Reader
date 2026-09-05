package com.moonkata.textreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.R
import com.moonkata.textreader.model.Chapter
import kotlin.math.roundToInt

/** When the table of contents opens, scroll up by this many items so the chapter currently being read is already visible near the top. */
private const val CONTEXT_CHAPTERS_ABOVE = 2

/** How far into the book `offset` sits, as a whole-number percent — shown as trailing context on TOC/search list rows (rounded rather than the reader corner indicator's decimal precision, since a scannable list of many rows favors a single glanceable number). */
internal fun formatPositionPercent(offset: Int, totalLength: Int): String {
    if (totalLength <= 0) return "0%"
    val percent = (offset.toFloat() / totalLength * 100).roundToInt().coerceIn(0, 100)
    return "$percent%"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(chapters: List<Chapter>, currentOffset: Int, fullTextLength: Int, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (chapters.isEmpty()) {
            Text(stringResource(R.string.toc_empty), modifier = Modifier.padding(24.dp))
        } else {
            val currentIndex = remember(chapters, currentOffset) {
                chapters.indexOfLast { it.charOffset <= currentOffset }.coerceAtLeast(0)
            }
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = (currentIndex - CONTEXT_CHAPTERS_ABOVE).coerceAtLeast(0),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), state = listState) {
                items(chapters.size) { index ->
                    val chapter = chapters[index]
                    val isCurrent = index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .clickable { onJump(chapter.charOffset) }
                            .padding(16.dp),
                    ) {
                        Text(
                            chapter.title,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatPositionPercent(chapter.charOffset, fullTextLength),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

package com.moonkata.textreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.model.Chapter

/** 목차를 열었을 때 지금 읽고 있는 챕터가 위쪽에 미리 보이도록 이만큼 앞에서부터 스크롤해둔다. */
private const val CONTEXT_CHAPTERS_ABOVE = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(chapters: List<Chapter>, currentOffset: Int, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (chapters.isEmpty()) {
            Text("목차를 찾을 수 없어요", modifier = Modifier.padding(24.dp))
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
                    Text(
                        chapter.title,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .clickable { onJump(chapter.charOffset) }
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

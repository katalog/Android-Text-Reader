package com.moonkata.textreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonkata.textreader.data.font.FontResolver
import com.moonkata.textreader.ui.theme.ReaderColors
import com.moonkata.textreader.util.binarySearchFloor
import kotlinx.coroutines.flow.filter

@Composable
fun ReaderScrollContent(viewModel: ReaderViewModel, uiState: ReaderUiState, readerColors: ReaderColors) {
    val context = LocalContext.current
    val settings = uiState.settings
    val fontFamily = remember(settings.fontFamilyId) { FontResolver.resolve(context, settings.fontFamilyId) }
    val listState = rememberLazyListState()

    val chapterOffsets = remember(uiState.chapters, settings.chapterJumpEnabled) {
        if (settings.chapterJumpEnabled) uiState.chapters.map { it.charOffset }.toSet() else emptySet()
    }
    val paragraphStartOffsets = remember(uiState.paragraphs) { uiState.paragraphs.map { it.startOffset } }
    val currentParagraphStartOffsets by rememberUpdatedState(paragraphStartOffsets)

    LaunchedEffect(uiState.paragraphs) {
        if (paragraphStartOffsets.isNotEmpty()) {
            val index = paragraphStartOffsets.binarySearchFloor(uiState.currentOffset)
            listState.scrollToItem(index.coerceIn(0, paragraphStartOffsets.lastIndex))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            val offsets = currentParagraphStartOffsets
            if (offsets.isEmpty()) return@collect
            when (event) {
                is ReaderNavEvent.JumpToOffset -> {
                    val index = offsets.binarySearchFloor(event.offset).coerceIn(0, offsets.lastIndex)
                    if (event.animate) listState.animateScrollToItem(index) else listState.scrollToItem(index)
                }
                ReaderNavEvent.RequestNextPage -> {
                    val target = (listState.firstVisibleItemIndex + 10).coerceAtMost(offsets.lastIndex)
                    listState.scrollToItem(target)
                }
                ReaderNavEvent.RequestPreviousPage -> {
                    val target = (listState.firstVisibleItemIndex - 10).coerceAtLeast(0)
                    listState.scrollToItem(target)
                }
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val index = listState.firstVisibleItemIndex
                val offset = currentParagraphStartOffsets.getOrNull(index) ?: return@collect
                viewModel.updateCurrentOffset(offset)
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = settings.marginHorizontalDp.dp,
                end = settings.marginHorizontalDp.dp,
                top = settings.marginTopDp.dp,
                bottom = settings.marginBottomDp.dp,
            ),
    ) {
        items(uiState.paragraphs.size) { index ->
            val paragraph = uiState.paragraphs[index]
            val isChapterHeading = paragraph.startOffset in chapterOffsets
            Text(
                text = paragraph.text,
                color = readerColors.text,
                fontFamily = fontFamily,
                fontSize = settings.fontSizeSp.sp,
                lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                letterSpacing = settings.letterSpacingSp.sp,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .then(
                        if (isChapterHeading) {
                            Modifier
                                .background(ChapterHighlightColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

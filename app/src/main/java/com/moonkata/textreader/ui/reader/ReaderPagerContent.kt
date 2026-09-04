package com.moonkata.textreader.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonkata.textreader.data.datastore.PageTransitionAnimation
import com.moonkata.textreader.data.font.FontResolver
import com.moonkata.textreader.data.parser.PaginationParams
import com.moonkata.textreader.ui.theme.ReaderColors

/** Highlight color for lines detected as chapters in chapter jump mode — a fixed value so it always stands out regardless of the reader theme. */
internal val ChapterHighlightColor = Color(0x664CAF50)

/**
 * Applies a highlight background to the lines corresponding to chapterOffsets within [text]
 * (which starts at baseOffset). Only the background color is changed — font weight (bold) is left
 * alone, because Paginator computes line breaks using the regular weight without knowing about this
 * weight change when splitting pages; applying bold here would widen the glyphs at actual render time,
 * causing the last line to appear slightly clipped past the page edge.
 */
internal fun buildChapterHighlightedText(text: String, baseOffset: Int, chapterOffsets: Set<Int>): AnnotatedString {
    if (chapterOffsets.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (offset in chapterOffsets) {
            val local = offset - baseOffset
            if (local < 0 || local >= text.length) continue
            val end = text.indexOf('\n', local).let { if (it == -1) text.length else it }
            addStyle(SpanStyle(background = ChapterHighlightColor), local, end)
        }
    }
}

/**
 * Does not hold the full list of pages for the book — the view model computes and hands over only
 * "the one page to show right now" ([ReaderUiState.currentPage]), and this composable just renders it
 * as-is. Next/previous/jump are all handled by the view model swapping out that state, so there's no
 * burden of keeping the Pager's pageCount/index continuously in sync asynchronously.
 */
@Composable
fun ReaderPagerContent(viewModel: ReaderViewModel, uiState: ReaderUiState, readerColors: ReaderColors) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val settings = uiState.settings
    val fontFamily = remember(settings.fontFamilyId) { FontResolver.resolve(context, settings.fontFamilyId) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(
                start = settings.marginHorizontalDp.dp,
                end = settings.marginHorizontalDp.dp,
                top = settings.marginTopDp.dp,
                bottom = settings.marginBottomDp.dp,
            ),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.toInt()
        val heightPx = with(density) { maxHeight.toPx() }.toInt()

        LaunchedEffect(
            widthPx, heightPx, settings.fontFamilyId, settings.fontSizeSp,
            settings.lineHeightMultiplier, settings.letterSpacingSp, settings.lineBreakMode, uiState.paragraphs,
        ) {
            if (widthPx > 0 && heightPx > 0 && uiState.paragraphs.isNotEmpty()) {
                viewModel.onViewportMeasured(
                    textMeasurer,
                    PaginationParams(
                        fontFamily = fontFamily,
                        fontSizeSp = settings.fontSizeSp.sp,
                        lineHeightMultiplier = settings.lineHeightMultiplier,
                        letterSpacingSp = settings.letterSpacingSp.sp,
                        contentWidthPx = widthPx,
                        contentHeightPx = heightPx,
                        textColor = readerColors.text,
                    ),
                )
            }
        }

        val currentPage = uiState.currentPage
        if (currentPage == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            val chapterOffsets = remember(uiState.chapters, settings.chapterJumpEnabled) {
                if (settings.chapterJumpEnabled) uiState.chapters.map { it.charOffset }.toSet() else emptySet()
            }

            AnimatedContent(
                targetState = currentPage,
                label = "readerPage",
                transitionSpec = {
                    // Enters from the right when the offset increases (next), from the left when it decreases (previous).
                    val forward = targetState.startOffset >= initialState.startOffset
                    when (settings.pageTransitionAnimation) {
                        PageTransitionAnimation.NONE -> EnterTransition.None togetherWith ExitTransition.None
                        PageTransitionAnimation.SLIDE -> if (forward) {
                            slideInHorizontally { width -> width } togetherWith slideOutHorizontally { width -> -width }
                        } else {
                            slideInHorizontally { width -> -width } togetherWith slideOutHorizontally { width -> width }
                        }
                        // The new page slides in covering the old page — the old page stays put and disappears as it's covered.
                        PageTransitionAnimation.COVER -> ContentTransform(
                            targetContentEnter = if (forward) slideInHorizontally { width -> width } else slideInHorizontally { width -> -width },
                            initialContentExit = ExitTransition.None,
                            targetContentZIndex = 1f,
                        )
                    }
                },
            ) { page ->
                val text = uiState.fullText.substring(page.startOffset, page.endOffset)
                val displayText = remember(text, page.startOffset, chapterOffsets) {
                    buildChapterHighlightedText(text, page.startOffset, chapterOffsets)
                }
                // Always pin to the top-left to prevent the page content from being vertically centered
                // (which would leave empty space at the top) when it doesn't fill the whole screen.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    Text(
                        text = displayText,
                        color = readerColors.text,
                        fontFamily = fontFamily,
                        fontSize = settings.fontSizeSp.sp,
                        lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                        letterSpacing = settings.letterSpacingSp.sp,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

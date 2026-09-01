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

/** 챕터 점프 모드에서 챕터 인식 줄을 표시하는 하이라이트 색 — 리더 테마와 무관하게 늘 눈에 띄도록 고정값 사용. */
internal val ChapterHighlightColor = Color(0x664CAF50)

/**
 * [text](baseOffset부터 시작) 안에서 chapterOffsets에 해당하는 줄에 하이라이트 배경을 입힌다.
 * 배경색만 바꾸고 글자 두께(bold)는 건드리지 않는다 — Paginator는 페이지를 나눌 때 이 굵기 변화를
 * 알지 못한 채 일반 굵기 기준으로 줄바꿈을 계산하므로, 여기서 bold를 적용하면 실제 렌더링 시 글자
 * 폭이 넓어져 마지막 줄이 페이지 밖으로 살짝 잘려 보이는 문제가 생긴다.
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
 * 책 전체 페이지 목록을 들고 있지 않는다 — 뷰모델이 "지금 보여줄 페이지 하나"([ReaderUiState.currentPage])만
 * 계산해서 넘겨주고, 여기서는 그걸 그대로 렌더링만 한다. 다음/이전/점프는 전부 뷰모델이 상태를 갈아끼우는
 * 것으로 처리되므로, Pager의 pageCount/인덱스를 비동기로 계속 동기화해야 하는 부담이 없다.
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
                    // 오프셋이 커지는 방향(다음)이면 오른쪽에서, 작아지는 방향(이전)이면 왼쪽에서 들어온다.
                    val forward = targetState.startOffset >= initialState.startOffset
                    when (settings.pageTransitionAnimation) {
                        PageTransitionAnimation.NONE -> EnterTransition.None togetherWith ExitTransition.None
                        PageTransitionAnimation.SLIDE -> if (forward) {
                            slideInHorizontally { width -> width } togetherWith slideOutHorizontally { width -> -width }
                        } else {
                            slideInHorizontally { width -> -width } togetherWith slideOutHorizontally { width -> width }
                        }
                        // 새 페이지가 옛 페이지 위로 덮으며 들어온다 — 옛 페이지는 그대로 있다가 덮여서 사라진다.
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
                // 페이지 분량이 한 화면을 다 못 채울 때 세로 가운데로 배치되어 위쪽에 빈 여백이 생기는 걸
                // 막기 위해 항상 맨 위·왼쪽에 붙인다.
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

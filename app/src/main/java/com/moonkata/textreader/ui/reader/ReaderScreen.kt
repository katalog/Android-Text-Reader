package com.moonkata.textreader.ui.reader

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moonkata.textreader.MainActivity
import com.moonkata.textreader.data.datastore.OrientationLock
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.SwipeTurnMode
import com.moonkata.textreader.data.datastore.TouchTurnMode
import com.moonkata.textreader.ui.theme.ReaderThemePresets
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: ReaderViewModel = viewModel(factory = ReaderViewModelFactory(application, bookId))
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings
    val activity = context as? MainActivity

    var showChrome by remember { mutableStateOf(true) }
    var showQuickSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

    // 로딩 중엔(제목이라도 보이게) 상하단바를 띄워두고, 로딩이 끝나면 탭 없이 자동으로 숨긴다.
    // isLoading은 책 하나당 true→false로 딱 한 번만 바뀌므로, 그 이후 사용자가 탭으로 다시 열어도
    // 이 이펙트가 재실행되어 도로 닫아버리는 일은 없다.
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            showChrome = false
        }
    }

    // 화면이 꺼지거나 홈으로 나가거나 다른 앱으로 전환되면(ON_STOP) 디바운스를 기다리지 않고 읽던
    // 위치를 로컬에 즉시 저장(그 직후 프로세스가 종료돼도 유실되지 않게) + 원격에도 체크포인트 반영.
    // 반대로 화면이 다시 보이게 되면(ON_START — 잠금 해제, 다른 앱에서 복귀 등) 그사이 다른 기기가
    // 더 멀리 읽었는지 다시 확인한다. 뒤로가기로 리더를 완전히 벗어나는 경우는 이 옵저버가 아니라
    // ReaderViewModel.onCleared에서 처리한다(ON_STOP은 액티비티가 살아있는 채로 멈출 때만 오고,
    // Navigation-Compose로 같은 액티비티 안에서 뒤로가기하면 안 옴).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    viewModel.flushPendingPosition()
                    viewModel.syncNowToRemote()
                }
                Lifecycle.Event.ON_START -> viewModel.onReaderResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 볼륨키로 페이지 넘기기
    DisposableEffect(settings.volumeKeyPagingEnabled) {
        activity?.volumeKeyHandler = if (settings.volumeKeyPagingEnabled) {
            { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> { viewModel.next(); true }
                    KeyEvent.KEYCODE_VOLUME_UP -> { viewModel.previous(); true }
                    else -> false
                }
            }
        } else {
            null
        }
        onDispose { activity?.volumeKeyHandler = null }
    }

    // 화면 꺼짐 방지
    DisposableEffect(settings.keepScreenOnEnabled) {
        val window = activity?.window
        if (settings.keepScreenOnEnabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 밝기 직접 조절 — 화면을 벗어나면 항상 원상복구
    DisposableEffect(settings.brightnessOverrideEnabled, settings.brightnessValue) {
        val window = activity?.window
        if (window != null) {
            val attrs = window.attributes
            attrs.screenBrightness = if (settings.brightnessOverrideEnabled) {
                settings.brightnessValue
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = attrs
        }
        onDispose {
            val attrs = window?.attributes
            if (window != null && attrs != null) {
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
        }
    }

    // 화면 방향 고정 — 화면을 벗어나면 항상 자동으로 복구
    DisposableEffect(settings.orientationLock) {
        activity?.requestedOrientation = when (settings.orientationLock) {
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationLock.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    val readerColors = ReaderThemePresets.forSettings(settings)

    // 리더 화면을 벗어나면 상태바/내비게이션 바를 원래 상태로 복구 — 딱 한 번만 원래 색을 기억해둔다.
    DisposableEffect(Unit) {
        val window = activity?.window
        val originalStatusBarColor = window?.statusBarColor
        val originalNavigationBarColor = window?.navigationBarColor
        onDispose {
            if (window != null) {
                if (originalStatusBarColor != null) window.statusBarColor = originalStatusBarColor
                if (originalNavigationBarColor != null) window.navigationBarColor = originalNavigationBarColor
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = true
                    window.isNavigationBarContrastEnforced = true
                }
            }
        }
    }

    // 홈/뒤로/최근 앱 버튼이 있는 시스템 내비게이션 바·상태 바를 읽기 테마 배경색에 맞춘다.
    // 기본적으로는 시스템이 가독성을 위해 자체 스크림을 덮어써서 읽기 테마와 무관하게 항상 같은 색으로 보인다.
    DisposableEffect(readerColors) {
        val window = activity?.window
        if (window != null) {
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            val isLightBackground = readerColors.background.luminance() > 0.5f
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = isLightBackground
                isAppearanceLightNavigationBars = isLightBackground
            }
        }
        onDispose {}
    }

    val progress = if (uiState.fullText.isNotEmpty()) uiState.currentOffset.toFloat() / uiState.fullText.length else 0f

    Box(Modifier.fillMaxSize().background(readerColors.background)) {
        if (uiState.isLoading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .pointerInput(settings.touchTurnMode, settings.swipeTurnMode) {
                        val swipeThresholdPx = 40.dp.toPx()
                        coroutineScope {
                            launch {
                                // 상하단바가 떠 있을 때는 탭 위치와 무관하게(실제 버튼 위가 아닌 한) 그냥 닫기만 한다 —
                                // 페이지 넘김과 동시에 발생해 혼란스러워지는 것을 막는다. 버튼 자체는 이 pointerInput보다
                                // 위(z-order)에 있어 자기 클릭을 먼저 소비하므로 여기까지 내려오지 않는다.
                                detectTapGestures(onTap = { offset ->
                                    if (showChrome) {
                                        showChrome = false
                                        return@detectTapGestures
                                    }
                                    val width = size.width
                                    val height = size.height
                                    when {
                                        offset.y < height * 0.3f -> showChrome = true
                                        offset.x < width * 0.5f -> {
                                            if (settings.touchTurnMode == TouchTurnMode.STANDARD) viewModel.previous() else viewModel.next()
                                        }
                                        else -> viewModel.next()
                                    }
                                })
                            }
                            launch {
                                var dragTotalX = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragTotalX = 0f },
                                    onHorizontalDrag = { change, dragAmount ->
                                        dragTotalX += dragAmount
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        // 왼쪽 스와이프(<-)는 다음 페이지로 넘기는 통상적인 방향이라 두 모드 모두 동일하게 처리한다.
                                        when {
                                            dragTotalX <= -swipeThresholdPx -> viewModel.next()
                                            dragTotalX >= swipeThresholdPx -> {
                                                if (settings.swipeTurnMode == SwipeTurnMode.STANDARD) viewModel.previous() else viewModel.next()
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    },
            ) {
                if (settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE) {
                    ReaderPagerContent(viewModel = viewModel, uiState = uiState, readerColors = readerColors)
                } else {
                    ReaderScrollContent(viewModel = viewModel, uiState = uiState, readerColors = readerColors)
                }
            }

            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ReaderTopBar(
                    title = uiState.book?.displayName ?: "",
                    readerColors = readerColors,
                    chapterJumpEnabled = settings.chapterJumpEnabled,
                    onBack = onBack,
                    onToc = { showToc = true },
                    onSearch = { showSearch = true },
                    onSettings = { showQuickSettings = true },
                    onToggleChapterJump = { viewModel.setChapterJumpEnabled(!settings.chapterJumpEnabled) },
                )
            }
            // 상단바가 숨겨져 있을 때도 읽은 비율을 놓치지 않도록 기본 화면에 항상 떠 있는 작은 표시.
            // 배경 없이 반투명 텍스트만 놓으면 마침 그 자리에 있는 본문 마지막 줄과 겹쳐 보여
            // "줄이 잘린 것처럼" 보이므로, 알약 모양 배경을 깔아 본문과 확실히 분리해 보이게 한다.
            if (!showChrome) {
                Surface(
                    color = readerColors.background.copy(alpha = 0.85f),
                    contentColor = readerColors.text,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .safeDrawingPadding()
                        .padding(8.dp),
                ) {
                    Text(
                        text = "%.3f%%".format(progress * 100),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    if (showQuickSettings) {
        QuickSettingsSheet(viewModel = viewModel, settings = settings, onDismiss = { showQuickSettings = false })
    }
    if (showToc) {
        TocSheet(
            chapters = uiState.chapters,
            currentOffset = uiState.currentOffset,
            onJump = { offset -> viewModel.jumpToOffset(offset); showToc = false },
            onDismiss = { showToc = false },
        )
    }
    if (showSearch) {
        SearchSheet(
            onSearch = viewModel::search,
            initialQuery = viewModel.lastSearchQuery,
            initialResults = viewModel.lastSearchResults,
            currentOffset = uiState.currentOffset,
            onJump = { offset -> viewModel.jumpToOffset(offset); showSearch = false },
            onDismiss = { showSearch = false },
        )
    }
    uiState.externalFurtherOffset?.let { externalOffset ->
        val totalLength = uiState.fullText.length
        val externalPercent = if (totalLength > 0) externalOffset.toFloat() / totalLength * 100 else 0f
        val currentPercent = if (totalLength > 0) uiState.currentOffset.toFloat() / totalLength * 100 else 0f
        AlertDialog(
            onDismissRequest = viewModel::dismissExternalPositionPrompt,
            title = { Text("다른 기기에서 더 읽으셨어요") },
            text = {
                Text(
                    "현재 %.1f%% 읽는 중 — 다른 기기는 %.1f%%까지 읽으셨네요. 그 위치로 이동할까요?"
                        .format(currentPercent, externalPercent)
                )
            },
            confirmButton = { TextButton(onClick = viewModel::jumpToExternalPosition) { Text("이동") } },
            dismissButton = { TextButton(onClick = viewModel::dismissExternalPositionPrompt) { Text("괜찮아요") } },
        )
    }
}

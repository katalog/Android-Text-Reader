package com.moonkata.textreader.ui.reader

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moonkata.textreader.MainActivity
import com.moonkata.textreader.R
import com.moonkata.textreader.data.datastore.OrientationLock
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.ui.theme.ReaderThemePresets
import kotlin.math.abs
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

    // Keep the top/bottom bars up while loading (so at least the title is visible), and auto-hide
    // them without a tap once loading finishes. isLoading only ever flips true→false once per book,
    // so this effect re-running later (e.g. the user re-opens the bars with a tap) never closes them
    // again on its own.
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            showChrome = false
        }
    }

    // Transient notices (e.g. "no chapter pattern found") — the app has no Snackbar/Toast
    // infrastructure yet, so a plain Android Toast is the simplest way to surface these.
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { messageRes ->
            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
        }
    }

    // When the screen turns off, goes home, or switches to another app (ON_STOP), save the reading
    // position locally right away without waiting for the debounce (so it isn't lost if the process
    // is killed right after) and also push a checkpoint remotely. Conversely, when the screen becomes
    // visible again (ON_START — unlock, returning from another app, etc.), re-check whether another
    // device read further in the meantime. Leaving the reader entirely via back press is handled by
    // ReaderViewModel.onCleared, not this observer (ON_STOP only fires while the activity stays alive
    // paused, and doesn't fire on back press within the same activity under Navigation-Compose).
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

    // Page turning via volume keys
    DisposableEffect(settings.volumeKeyPagingEnabled) {
        activity?.volumeKeyHandler = if (settings.volumeKeyPagingEnabled) {
            { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> { viewModel.nextPage(); true }
                    KeyEvent.KEYCODE_VOLUME_UP -> { viewModel.previousPage(); true }
                    else -> false
                }
            }
        } else {
            null
        }
        onDispose { activity?.volumeKeyHandler = null }
    }

    // Keep screen on
    DisposableEffect(settings.keepScreenOnEnabled) {
        val window = activity?.window
        if (settings.keepScreenOnEnabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Manual brightness override — always restored when leaving this screen
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

    // Orientation lock — always restored to auto when leaving this screen
    DisposableEffect(settings.orientationLock) {
        activity?.requestedOrientation = when (settings.orientationLock) {
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationLock.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    val readerColors = ReaderThemePresets.forSettings(settings)

    // Restore the status/navigation bars to their original state when leaving the reader screen —
    // remember the original colors exactly once.
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

    // Match the system status bar and navigation bar (home/back/recents buttons) to the reader
    // theme's background color. By default the system overlays its own scrim for readability, so
    // they'd otherwise always look the same regardless of the reader theme.
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
                    .pointerInput(
                        settings.touchLeftAction, settings.touchRightAction,
                        settings.swipeLeftAction, settings.swipeRightAction,
                        settings.swipeUpAction, settings.swipeDownAction,
                        settings.pageTurnMode,
                    ) {
                        val swipeThresholdPx = 40.dp.toPx()
                        coroutineScope {
                            launch {
                                // While the top/bottom bars are showing, any tap (unless it's actually on a
                                // button) just closes them, regardless of position — this avoids a page turn
                                // happening at the same time and causing confusion. The buttons themselves sit
                                // above this pointerInput in z-order and consume their own clicks first, so
                                // taps on them never reach here.
                                detectTapGestures(onTap = { offset ->
                                    if (showChrome) {
                                        showChrome = false
                                        return@detectTapGestures
                                    }
                                    val width = size.width
                                    val height = size.height
                                    when {
                                        offset.y < height * 0.3f -> showChrome = true
                                        offset.x < width * 0.5f -> viewModel.performGestureAction(settings.touchLeftAction)
                                        else -> viewModel.performGestureAction(settings.touchRightAction)
                                    }
                                })
                            }
                            launch {
                                if (settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE) {
                                    // Both axes are tracked and only the axis with the larger total movement is
                                    // acted on, so a diagonal drag doesn't ambiguously trigger both a horizontal
                                    // and a vertical action.
                                    var dragTotalX = 0f
                                    var dragTotalY = 0f
                                    detectDragGestures(
                                        onDragStart = { dragTotalX = 0f; dragTotalY = 0f },
                                        onDrag = { change, dragAmount ->
                                            dragTotalX += dragAmount.x
                                            dragTotalY += dragAmount.y
                                            change.consume()
                                        },
                                        onDragEnd = {
                                            if (abs(dragTotalX) >= abs(dragTotalY)) {
                                                when {
                                                    dragTotalX <= -swipeThresholdPx -> viewModel.performGestureAction(settings.swipeLeftAction)
                                                    dragTotalX >= swipeThresholdPx -> viewModel.performGestureAction(settings.swipeRightAction)
                                                }
                                            } else {
                                                when {
                                                    dragTotalY <= -swipeThresholdPx -> viewModel.performGestureAction(settings.swipeUpAction)
                                                    dragTotalY >= swipeThresholdPx -> viewModel.performGestureAction(settings.swipeDownAction)
                                                }
                                            }
                                        },
                                    )
                                } else {
                                    // Scroll mode: vertical dragging is already the scroll gesture itself
                                    // (ReaderScrollContent's LazyColumn), so only horizontal swipe is
                                    // available here — swipe up/down actions don't apply in this mode.
                                    var dragTotalX = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragTotalX = 0f },
                                        onHorizontalDrag = { change, dragAmount ->
                                            dragTotalX += dragAmount
                                            change.consume()
                                        },
                                        onDragEnd = {
                                            when {
                                                dragTotalX <= -swipeThresholdPx -> viewModel.performGestureAction(settings.swipeLeftAction)
                                                dragTotalX >= swipeThresholdPx -> viewModel.performGestureAction(settings.swipeRightAction)
                                            }
                                        },
                                    )
                                }
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
                    onBack = onBack,
                    onToc = { showToc = true },
                    onSearch = { showSearch = true },
                    onSettings = { showQuickSettings = true },
                )
            }
            // A small always-on indicator so the read percentage isn't lost even while the top bar is
            // hidden. Plain semi-transparent text with no background would overlap whatever body text
            // happens to be on the last line there and look like a "cut-off line", so a pill-shaped
            // background keeps it clearly separated from the body text.
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
            title = { Text(stringResource(R.string.reader_external_position_title)) },
            text = {
                Text(stringResource(R.string.reader_external_position_message, currentPercent, externalPercent))
            },
            confirmButton = { TextButton(onClick = viewModel::jumpToExternalPosition) { Text(stringResource(R.string.reader_external_position_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissExternalPositionPrompt) { Text(stringResource(R.string.reader_external_position_dismiss)) } },
        )
    }
}

package com.moonkata.textreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moonkata.textreader.R
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.LineBreakMode
import com.moonkata.textreader.data.datastore.OrientationLock
import com.moonkata.textreader.data.datastore.PageTransitionAnimation
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.datastore.SwipeTurnMode
import com.moonkata.textreader.data.datastore.ThemePreset
import com.moonkata.textreader.data.datastore.TouchTurnMode
import com.moonkata.textreader.data.sync.QrPairingPayload
import com.moonkata.textreader.ui.SettingsController
import com.moonkata.textreader.ui.qr.QrScannerDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSettingsSheet(viewModel: SettingsController, settings: ReaderSettings, onDismiss: () -> Unit) {
    var showFontPicker by remember { mutableStateOf(false) }
    var showChapterPatterns by remember { mutableStateOf(false) }

    // The shared secret isn't committed on a focus-loss (blur) event — on a real device that event
    // sometimes doesn't fire reliably when the sheet closes suddenly via back press or an outside
    // tap. Instead it's held as local draft state only, and committed at onDismissRequest, the one
    // point every dismissal path passes through — a successful connection test already commits it
    // separately right away (together with the verified state), so this path is the fallback for
    // "closed without testing."
    var sharedSecretDraft by remember { mutableStateOf(settings.supabaseSharedSecret) }
    var testingSync by remember { mutableStateOf(false) }
    var syncTestFailed by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val runSyncTest: (String) -> Unit = { secret ->
        testingSync = true
        syncTestFailed = false
        coroutineScope.launch {
            val success = viewModel.testSupabaseConnection(secret)
            testingSync = false
            syncTestFailed = !success
        }
    }

    val dismissAndCommitSync: () -> Unit = {
        if (sharedSecretDraft != settings.supabaseSharedSecret) viewModel.setSupabaseSharedSecret(sharedSecretDraft)
        onDismiss()
    }

    // A full-screen Dialog instead of a ModalBottomSheet — this sheet's content is long enough that
    // scrolling through it on a bottom sheet made an up/down swipe easy to mistake for the sheet's
    // own swipe-to-dismiss gesture, closing it mid-scroll. A Dialog only closes via the back button/
    // gesture or system back, never from a vertical drag.
    Dialog(onDismissRequest = dismissAndCommitSync, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_screen_title)) },
                    navigationIcon = {
                        IconButton(onClick = dismissAndCommitSync) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.reader_back_desc))
                        }
                    },
                )
            },
        ) { padding ->
        Column(Modifier.fillMaxWidth().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.settings_section_font), style = MaterialTheme.typography.titleMedium)
            LabeledStepper(stringResource(R.string.settings_font_size), settings.fontSizeSp, 1f, 12f..32f, format = { "${it.toInt()}sp" }) { viewModel.setFontSizeSp(it) }
            LabeledStepper(stringResource(R.string.settings_line_height), settings.lineHeightMultiplier, 0.1f, 1.0f..2.5f, format = { "%.1f".format(it) }) { viewModel.setLineHeightMultiplier(it) }
            LabeledStepper(stringResource(R.string.settings_letter_spacing), settings.letterSpacingSp, 0.5f, -1f..3f, format = { "%.1f".format(it) }) { viewModel.setLetterSpacingSp(it) }
            OutlinedButton(onClick = { showFontPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_font_picker_button))
            }

            SectionDivider()
            Text(stringResource(R.string.settings_section_margins), style = MaterialTheme.typography.titleMedium)
            LabeledStepper(stringResource(R.string.settings_margin_horizontal), settings.marginHorizontalDp, 4f, 0f..80f, format = { "${it.toInt()}dp" }) { viewModel.setMarginHorizontalDp(it) }
            LabeledStepper(stringResource(R.string.settings_margin_top), settings.marginTopDp, 4f, 0f..80f, format = { "${it.toInt()}dp" }) { viewModel.setMarginTopDp(it) }
            LabeledStepper(stringResource(R.string.settings_margin_bottom), settings.marginBottomDp, 4f, 0f..80f, format = { "${it.toInt()}dp" }) { viewModel.setMarginBottomDp(it) }

            SectionDivider()
            Text(stringResource(R.string.settings_section_theme), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemePreset.LIGHT to R.string.settings_theme_light,
                    ThemePreset.DARK to R.string.settings_theme_dark,
                    ThemePreset.SEPIA to R.string.settings_theme_sepia,
                ).forEach { (preset, labelRes) ->
                    FilterChip(
                        selected = settings.themePreset == preset,
                        onClick = { viewModel.setThemePreset(preset) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            SectionDivider()
            Text(stringResource(R.string.settings_section_page_turn_mode), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE,
                    onClick = { viewModel.setPageTurnMode(PageTurnMode.HORIZONTAL_PAGE) },
                    label = { Text(stringResource(R.string.settings_page_turn_paged)) },
                )
                FilterChip(
                    selected = settings.pageTurnMode == PageTurnMode.VERTICAL_SCROLL,
                    onClick = { viewModel.setPageTurnMode(PageTurnMode.VERTICAL_SCROLL) },
                    label = { Text(stringResource(R.string.settings_page_turn_scroll)) },
                )
            }

            SectionDivider()
            Text(stringResource(R.string.settings_section_transition_animation), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    PageTransitionAnimation.NONE to R.string.settings_transition_none,
                    PageTransitionAnimation.SLIDE to R.string.settings_transition_slide,
                    PageTransitionAnimation.COVER to R.string.settings_transition_cover,
                ).forEach { (animation, labelRes) ->
                    FilterChip(
                        selected = settings.pageTransitionAnimation == animation,
                        onClick = { viewModel.setPageTransitionAnimation(animation) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            SectionDivider()
            Text(stringResource(R.string.settings_section_page_turn_options), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_touch_zones), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.touchTurnMode == TouchTurnMode.STANDARD,
                    onClick = { viewModel.setTouchTurnMode(TouchTurnMode.STANDARD) },
                    label = { Text(stringResource(R.string.settings_touch_standard)) },
                )
                FilterChip(
                    selected = settings.touchTurnMode == TouchTurnMode.BOTH_NEXT,
                    onClick = { viewModel.setTouchTurnMode(TouchTurnMode.BOTH_NEXT) },
                    label = { Text(stringResource(R.string.settings_touch_both_next)) },
                )
            }
            Text(stringResource(R.string.settings_swipe), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.swipeTurnMode == SwipeTurnMode.STANDARD,
                    onClick = { viewModel.setSwipeTurnMode(SwipeTurnMode.STANDARD) },
                    label = { Text(stringResource(R.string.settings_swipe_standard)) },
                )
                FilterChip(
                    selected = settings.swipeTurnMode == SwipeTurnMode.BOTH_NEXT,
                    onClick = { viewModel.setSwipeTurnMode(SwipeTurnMode.BOTH_NEXT) },
                    label = { Text(stringResource(R.string.settings_swipe_both_next)) },
                )
            }

            SectionDivider()
            Text(stringResource(R.string.settings_section_line_break), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.lineBreakMode == LineBreakMode.PRESERVE,
                    onClick = { viewModel.setLineBreakMode(LineBreakMode.PRESERVE) },
                    label = { Text(stringResource(R.string.settings_line_break_preserve)) },
                )
                FilterChip(
                    selected = settings.lineBreakMode == LineBreakMode.REFLOW,
                    onClick = { viewModel.setLineBreakMode(LineBreakMode.REFLOW) },
                    label = { Text(stringResource(R.string.settings_line_break_reflow)) },
                )
            }

            SectionDivider()
            Text(stringResource(R.string.settings_section_chapter_jump), style = MaterialTheme.typography.titleMedium)
            SwitchRow(stringResource(R.string.settings_chapter_jump_enabled), settings.chapterJumpEnabled) { viewModel.setChapterJumpEnabled(it) }
            LabeledStepper(stringResource(R.string.settings_chapter_jump_divisions), settings.chapterJumpDivisions.toFloat(), 1f, 2f..10f, format = { "${it.toInt()}" }) {
                viewModel.setChapterJumpDivisions(it.toInt())
            }
            OutlinedButton(onClick = { showChapterPatterns = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_chapter_pattern_button))
            }

            SectionDivider()
            Text(stringResource(R.string.settings_section_screen), style = MaterialTheme.typography.titleMedium)
            SwitchRow(stringResource(R.string.settings_keep_screen_on), settings.keepScreenOnEnabled) { viewModel.setKeepScreenOnEnabled(it) }
            SwitchRow(stringResource(R.string.settings_volume_key_paging), settings.volumeKeyPagingEnabled) { viewModel.setVolumeKeyPagingEnabled(it) }
            SwitchRow(stringResource(R.string.settings_brightness_override), settings.brightnessOverrideEnabled) { viewModel.setBrightnessOverrideEnabled(it) }
            if (settings.brightnessOverrideEnabled) {
                LabeledStepper(stringResource(R.string.settings_brightness), settings.brightnessValue, 0.05f, 0.05f..1f, format = { "${(it * 100).toInt()}%" }) {
                    viewModel.setBrightnessValue(it)
                }
            }
            Text(stringResource(R.string.settings_orientation), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    OrientationLock.AUTO to R.string.settings_orientation_auto,
                    OrientationLock.PORTRAIT to R.string.settings_orientation_portrait,
                    OrientationLock.LANDSCAPE to R.string.settings_orientation_landscape,
                ).forEach { (lock, labelRes) ->
                    FilterChip(
                        selected = settings.orientationLock == lock,
                        onClick = { viewModel.setOrientationLock(lock) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            SectionDivider()
            Text(stringResource(R.string.settings_section_auto_advance), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AutoAdvanceMode.OFF to R.string.settings_auto_advance_off,
                    AutoAdvanceMode.TIMER to R.string.settings_auto_advance_timer,
                    AutoAdvanceMode.TTS to R.string.settings_auto_advance_tts,
                ).forEach { (mode, labelRes) ->
                    FilterChip(
                        selected = settings.autoAdvanceMode == mode,
                        onClick = { viewModel.setAutoAdvanceMode(mode) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
            if (settings.autoAdvanceMode == AutoAdvanceMode.TIMER) {
                val intervalFormat = stringResource(R.string.settings_auto_advance_interval)
                LabeledStepper(stringResource(R.string.settings_auto_advance_interval_label), settings.autoPageTurnIntervalSeconds.toFloat(), 5f, 3f..60f, format = { intervalFormat.format(it.toInt()) }) {
                    viewModel.setAutoPageTurnIntervalSeconds(it.toInt())
                }
            }

            SectionDivider()
            Text(stringResource(R.string.settings_section_vscode_sync), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_vscode_sync_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showQrScanner = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_connect_via_qr))
            }
            Spacer(Modifier.height(8.dp))
            SyncSettingField(stringResource(R.string.settings_shared_secret), sharedSecretDraft, isSecret = true) {
                sharedSecretDraft = it
                syncTestFailed = false
            }
            val isVerified = sharedSecretDraft.isNotBlank() && sharedSecretDraft == settings.supabaseVerifiedSecret
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { runSyncTest(sharedSecretDraft) },
                    enabled = sharedSecretDraft.isNotBlank() && !testingSync,
                ) {
                    if (testingSync) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.settings_test_connection))
                    }
                }
                when {
                    isVerified -> {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32))
                        Text(stringResource(R.string.settings_connected), color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodyMedium)
                    }
                    syncTestFailed -> Text(
                        stringResource(R.string.settings_connection_failed, viewModel.lastSupabaseTestError() ?: stringResource(R.string.settings_check_secret)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
        }
    }

    if (showFontPicker) {
        FontPickerSheet(viewModel = viewModel, settings = settings, onDismiss = { showFontPicker = false })
    }
    if (showChapterPatterns) {
        ChapterPatternSheet(viewModel = viewModel, settings = settings, onDismiss = { showChapterPatterns = false })
    }
    if (showQrScanner) {
        // On a successful QR scan, fill in the secret and immediately auto-run the connection test —
        // so one scan is all it takes. An invalid QR/unknown type/denied permission is already
        // closed via onDismiss by QrScannerDialog itself, so we do nothing here and just let the
        // user fall back to the manual field above once the sheet closes.
        QrScannerDialog(
            onResult = { payload ->
                showQrScanner = false
                if (payload is QrPairingPayload.VscodeSync) {
                    sharedSecretDraft = payload.secret
                    runSyncTest(payload.secret)
                }
            },
            onDismiss = { showQrScanner = false },
        )
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
}

/**
 * A pure controlled field — doesn't write to DataStore directly, only updates the local draft state
 * held by the parent (QuickSettingsSheet). The actual commit happens once, when the sheet closes
 * (a DataStore round-trip write on every keystroke would be slow, and the cursor position could get
 * disrupted each time [settings] re-emits in response while the user is still typing).
 */
@Composable
private fun SyncSettingField(label: String, value: String, isSecret: Boolean = false, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    // Wrap the whole Row in toggleable — this widens the touch target from just the switch thumb
    // (small) to the label too (Material accessibility guidance), and as a result the label+switch
    // merge into one node in the semantics tree (mergeDescendants), so the switch can be found and
    // operated by its label text alone. With several switches on this sheet, if the Row didn't form
    // a semantic boundary (a plain Row doesn't, by default), they'd all flatten into siblings under
    // the same parent with no way to tell them apart by label text alone.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A numeric control adjusted with +/- buttons — easier to hit precisely with a finger than a slider. */
@Composable
private fun LabeledStepper(
    label: String,
    value: Float,
    step: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(onClick = { onValueChange((value - step).coerceIn(range)) }, enabled = value > range.start) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.settings_stepper_decrease_desc, label))
        }
        Text(format(value), modifier = Modifier.widthIn(min = 48.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { onValueChange((value + step).coerceIn(range)) }, enabled = value < range.endInclusive) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_stepper_increase_desc, label))
        }
    }
}

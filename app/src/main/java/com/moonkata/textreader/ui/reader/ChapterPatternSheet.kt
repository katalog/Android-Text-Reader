package com.moonkata.textreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.R
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.parser.ChapterPatternCatalog
import com.moonkata.textreader.ui.SettingsController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterPatternSheet(viewModel: SettingsController, settings: ReaderSettings, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val dummyFocusRequester = remember { FocusRequester() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        // LocalSoftwareKeyboardController must be read inside this sheet (a separate sub-composition)
        // to actually act on the text field that currently holds focus — reading it outside the
        // ModalBottomSheet has no effect.
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current

        // When the sheet is dismissed by swiping down or tapping outside rather than the back button,
        // a focused text field can disappear from the composition with no cleanup — that leaves the
        // IME (and the bottom padding it causes) open, permanently obscuring that much of the body in
        // page mode. Force-clear focus whenever the sheet leaves by any path so the IME always gets
        // cleaned up.
        DisposableEffect(Unit) {
            onDispose { focusManager.clearFocus(force = true) }
        }

        // Turn off the sheet's own back-press handling (shouldDismissOnBackPress = false) so we
        // handle back ourselves — while the keyboard is up, this back press only closes the keyboard;
        // the next one closes the sheet (same behavior as a "done" button).
        BackHandler {
            if (fieldFocused) {
                keyboardController?.hide()
                // clearFocus() alone has nothing to hand focus off to, so the system immediately
                // returns focus to the same field — move focus to an invisible dummy target instead
                // to prevent that.
                dummyFocusRequester.requestFocus()
            } else {
                onDismiss()
            }
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Spacer(
                Modifier
                    .size(1.dp)
                    .focusRequester(dummyFocusRequester)
                    .focusable(),
            )
            Text(stringResource(R.string.chapter_pattern_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.chapter_pattern_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            ChapterPatternCatalog.presets.forEach { preset ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(preset.labelRes), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.chapter_pattern_example_prefix, stringResource(preset.exampleRes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = preset.id in settings.chapterPatternEnabledIds,
                        onCheckedChange = { checked -> viewModel.toggleChapterPattern(preset.id, checked) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.chapter_pattern_custom_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; showError = false },
                    placeholder = { Text(stringResource(R.string.chapter_pattern_custom_placeholder)) },
                    isError = showError,
                    singleLine = true,
                    modifier = Modifier.weight(1f).onFocusChanged { fieldFocused = it.isFocused },
                )
                IconButton(onClick = {
                    if (viewModel.addCustomChapterPattern(input.trim())) {
                        input = ""
                    } else {
                        showError = true
                    }
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chapter_pattern_add_desc))
                }
            }
            if (showError) {
                Text(
                    stringResource(R.string.chapter_pattern_invalid_regex),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (settings.chapterCustomPatterns.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().height((settings.chapterCustomPatterns.size * 56).coerceAtMost(280).dp)) {
                    items(settings.chapterCustomPatterns.toList()) { pattern ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(pattern, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { viewModel.removeCustomChapterPattern(pattern) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.chapter_pattern_delete_desc))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

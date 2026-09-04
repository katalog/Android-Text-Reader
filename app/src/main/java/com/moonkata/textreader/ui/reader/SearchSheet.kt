package com.moonkata.textreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.R
import com.moonkata.textreader.model.SearchResult
import kotlin.math.abs

/** When the results list opens, scroll up by this many items so the result nearest the current reading position is already visible near the top. */
private const val CONTEXT_RESULTS_ABOVE = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    onSearch: (String) -> List<SearchResult>,
    initialQuery: String,
    initialResults: List<SearchResult>,
    currentOffset: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Use TextFieldValue to control the cursor position directly — if there's an existing query when
    // the search field opens, put the cursor at the end so it can be backspaced away immediately
    // (an empty string's end is naturally its start, so this is a no-op when there's no query yet).
    var query by remember { mutableStateOf(TextFieldValue(text = initialQuery, selection = TextRange(initialQuery.length))) }
    var results by remember { mutableStateOf(initialResults) }
    var fieldFocused by remember { mutableStateOf(false) }
    val dummyFocusRequester = remember { FocusRequester() }
    val textFieldFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

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

        fun runSearch() {
            results = onSearch(query.text)
            keyboardController?.hide()
        }

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Spacer(
                Modifier
                    .size(1.dp)
                    .focusRequester(dummyFocusRequester)
                    .focusable(),
            )
            OutlinedTextField(
                value = query,
                // Doesn't search on every keystroke — only runs once typing is done and the user
                // presses the search button (or the keyboard's search action), so results don't
                // keep changing mid-typing.
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_field_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                trailingIcon = {
                    IconButton(onClick = { runSearch() }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_desc))
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(textFieldFocusRequester)
                    .onFocusChanged { fieldFocused = it.isFocused },
            )
            // With a dummy focus target present, the sheet could steal focus automatically when it
            // appears — explicitly request focus on the search field to guarantee the keyboard still
            // pops up as soon as the sheet opens.
            LaunchedEffect(Unit) {
                textFieldFocusRequester.requestFocus()
            }
            Spacer(Modifier.height(8.dp))

            // The result nearest the current reading position — as soon as the list opens (whether
            // resuming a previous search's results or a fresh one), scroll to and visibly highlight
            // whatever's near that position.
            val nearestIndex = remember(results, currentOffset) {
                if (results.isEmpty()) -1 else results.indices.minBy { abs(results[it].offset - currentOffset) }
            }
            LaunchedEffect(results) {
                if (nearestIndex >= 0) {
                    listState.scrollToItem((nearestIndex - CONTEXT_RESULTS_ABOVE).coerceAtLeast(0))
                }
            }

            LazyColumn(Modifier.heightIn(max = 400.dp), state = listState) {
                items(results.size) { index ->
                    val result = results[index]
                    val isNearest = index == nearestIndex
                    Text(
                        result.snippet,
                        fontWeight = if (isNearest) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isNearest) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .clickable { onJump(result.offset) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

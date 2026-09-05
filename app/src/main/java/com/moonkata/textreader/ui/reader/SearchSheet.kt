package com.moonkata.textreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/**
 * A full-screen search "screen" (not a nav destination — just a full-size composable drawn as a
 * sibling over ReaderScreen's main content, the same way the bottom sheets it replaced were) rather
 * than a ModalBottomSheet. A sheet with an auto-focused text field fought with the keyboard's own
 * slide-up animation and needed a two-step back-press ("first press closes the keyboard, second
 * closes the sheet") to feel right; full-screen collapses that to one back press, since the focused
 * field simply leaves composition along with everything else.
 */
@Composable
fun SearchSheet(
    onSearch: (String) -> List<SearchResult>,
    initialQuery: String,
    initialResults: List<SearchResult>,
    currentOffset: Int,
    fullTextLength: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Use TextFieldValue to control the cursor position directly — if there's an existing query when
    // the search field opens, put the cursor at the end so it can be backspaced away immediately
    // (an empty string's end is naturally its start, so this is a no-op when there's no query yet).
    var query by remember { mutableStateOf(TextFieldValue(text = initialQuery, selection = TextRange(initialQuery.length))) }
    var results by remember { mutableStateOf(initialResults) }
    val textFieldFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // If this screen leaves composition by a path other than the back handler below (shouldn't
    // normally happen, but cheap insurance), make sure a focused field doesn't leave the IME
    // dangling open behind whatever comes back into view.
    DisposableEffect(Unit) {
        onDispose { focusManager.clearFocus(force = true) }
    }

    // A single back press always just exits this screen — no keyboard-then-screen two-step needed,
    // since the focused text field disappears along with everything else and the IME closes as a
    // natural side effect.
    BackHandler(onBack = onDismiss)

    fun runSearch() {
        results = onSearch(query.text)
        keyboardController?.hide()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.reader_back_desc))
                }
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
                    modifier = Modifier.weight(1f).focusRequester(textFieldFocusRequester),
                )
            }
            LaunchedEffect(Unit) {
                textFieldFocusRequester.requestFocus()
            }

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

            LazyColumn(Modifier.weight(1f), state = listState) {
                items(results.size) { index ->
                    val result = results[index]
                    val isNearest = index == nearestIndex
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isNearest) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .clickable { onJump(result.offset) }
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                    ) {
                        Text(
                            result.snippet,
                            fontWeight = if (isNearest) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatPositionPercent(result.offset, fullTextLength),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

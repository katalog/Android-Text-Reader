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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.model.SearchResult
import kotlin.math.abs

/** 검색 결과 목록을 열었을 때 지금 읽고 있는 위치와 가장 가까운 결과가 위쪽에 미리 보이도록 이만큼 앞에서부터 스크롤해둔다. */
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
    // TextFieldValue로 커서 위치까지 직접 제어한다 — 검색창을 열 때 기존 검색어가 있으면 끝으로
    // 커서를 두어 곧바로 백스페이스로 지울 수 있게 하고(없으면 빈 문자열 끝=처음이라 자연히 맨 앞).
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
        // LocalSoftwareKeyboardController는 이 시트 내부(별도 서브 컴포지션)에서 읽어야 실제로 포커스를
        // 쥐고 있는 텍스트 필드에 작동한다 — ModalBottomSheet 바깥에서 읽으면 아무 효과가 없다.
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current

        // 뒤로가기가 아니라 스와이프로 내리거나 바깥을 탭해서 닫을 때는 포커스가 켜진 텍스트 필드가
        // 정리 없이 그대로 컴포지션에서 사라진다 — 이러면 IME(및 그로 인한 하단 패딩)가 닫히지
        // 않고 남아, 페이지 모드에서 그 높이만큼 본문이 가려진 채로 굳어버린다. 시트가 어떤 경로로든
        // 사라질 때 포커스를 강제로 비워서 IME가 항상 정리되게 한다.
        DisposableEffect(Unit) {
            onDispose { focusManager.clearFocus(force = true) }
        }

        // 시트 자신의 뒤로가기 처리를 꺼서(shouldDismissOnBackPress = false), 뒤로가기를 우리가 직접 받는다 —
        // 키보드가 떠 있으면 이번 뒤로가기는 키보드만 닫고, 다음 뒤로가기에 시트를 닫는다("완료"와 같은 동작).
        BackHandler {
            if (fieldFocused) {
                keyboardController?.hide()
                // clearFocus()만으로는 포커스를 넘겨받을 다른 요소가 없어서 시스템이 곧바로 같은 필드에
                // 포커스를 되돌려버린다 — 보이지 않는 더미 포커스 대상으로 포커스를 옮겨 이를 막는다.
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
                // 입력할 때마다 바로 검색하지 않는다 — 입력을 다 마치고 검색 버튼(또는 키보드의 검색
                // 액션)을 눌러야 실행되게 해서, 타이핑 중간중간 결과가 계속 바뀌는 걸 막는다.
                onValueChange = { query = it },
                label = { Text("본문 검색") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                trailingIcon = {
                    IconButton(onClick = { runSearch() }) {
                        Icon(Icons.Default.Search, contentDescription = "검색")
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(textFieldFocusRequester)
                    .onFocusChanged { fieldFocused = it.isFocused },
            )
            // 더미 포커스 대상이 있으면 시트가 뜰 때 자동으로 초점을 뺏길 수 있어, 검색창에 명시적으로
            // 초점을 요청해 열리자마자 키보드가 뜨는 원래 동작을 보장한다.
            LaunchedEffect(Unit) {
                textFieldFocusRequester.requestFocus()
            }
            Spacer(Modifier.height(8.dp))

            // 지금 읽고 있는 위치와 가장 가까운 결과 — 목록을 열자마자(이전 검색 결과를 이어서 보든,
            // 새로 검색했든) 그 근처가 보이도록 스크롤하고, 눈에 띄게 강조한다.
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

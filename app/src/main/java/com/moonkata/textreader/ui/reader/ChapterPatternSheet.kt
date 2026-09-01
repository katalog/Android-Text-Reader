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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.parser.ChapterPatternCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterPatternSheet(viewModel: ReaderViewModel, settings: ReaderSettings, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val dummyFocusRequester = remember { FocusRequester() }

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

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Spacer(
                Modifier
                    .size(1.dp)
                    .focusRequester(dummyFocusRequester)
                    .focusable(),
            )
            Text("챕터 인식 패턴", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "아래 패턴 중 하나라도 한 줄 전체와 일치하면 챕터 제목으로 인식합니다.",
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
                        Text(preset.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "예: ${preset.example}",
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
            Text("커스텀 정규식 추가", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; showError = false },
                    placeholder = { Text("""예: ^Vol\.\s*\d+""") },
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
                    Icon(Icons.Default.Add, contentDescription = "추가")
                }
            }
            if (showError) {
                Text(
                    "올바른 정규식이 아니에요",
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
                                Icon(Icons.Default.Delete, contentDescription = "삭제")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

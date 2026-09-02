package com.moonkata.textreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.LineBreakMode
import com.moonkata.textreader.data.datastore.OrientationLock
import com.moonkata.textreader.data.datastore.PageTransitionAnimation
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.datastore.SwipeTurnMode
import com.moonkata.textreader.data.datastore.ThemePreset
import com.moonkata.textreader.data.datastore.TouchTurnMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSettingsSheet(viewModel: ReaderViewModel, settings: ReaderSettings, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showFontPicker by remember { mutableStateOf(false) }
    var showChapterPatterns by remember { mutableStateOf(false) }

    // 공유 시크릿은 포커스 상실(blur) 이벤트에 기대어 커밋하지 않는다 — 뒤로가기/바깥 탭으로 시트가
    // 갑자기 닫힐 때 그 이벤트가 안정적으로 안 오는 경우가 있었다(실기기 확인). 대신 로컬 초안 상태로만
    // 들고 있다가, 시트가 닫히는 모든 경로가 공통으로 거치는 onDismissRequest 시점에 커밋한다 —
    // 연결 테스트에 성공하면 그 즉시 별도로 커밋되므로(검증 상태와 함께) 이 경로는 "테스트 안 해보고
    // 그냥 닫은" 경우의 폴백이다.
    var sharedSecretDraft by remember { mutableStateOf(settings.supabaseSharedSecret) }
    var testingSync by remember { mutableStateOf(false) }
    var syncTestFailed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val dismissAndCommitSync: () -> Unit = {
        if (sharedSecretDraft != settings.supabaseSharedSecret) viewModel.setSupabaseSharedSecret(sharedSecretDraft)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = dismissAndCommitSync, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("글자", style = MaterialTheme.typography.titleMedium)
            LabeledStepper("크기", settings.fontSizeSp, 1f, 12f..32f, format = { "${it.toInt()}sp" }) { viewModel.setFontSizeSp(it) }
            LabeledStepper("줄간격", settings.lineHeightMultiplier, 0.1f, 1.0f..2.5f, format = { "%.1f".format(it) }) { viewModel.setLineHeightMultiplier(it) }
            LabeledStepper("자간", settings.letterSpacingSp, 0.5f, -1f..3f, format = { "%.1f".format(it) }) { viewModel.setLetterSpacingSp(it) }
            OutlinedButton(onClick = { showFontPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("폰트 선택 / 다운로드")
            }

            SectionDivider()
            Text("여백", style = MaterialTheme.typography.titleMedium)
            LabeledStepper("좌우", settings.marginHorizontalDp, 4f, 0f..80f, format = { "${it.toInt()}dp" }) { viewModel.setMarginHorizontalDp(it) }
            LabeledStepper("위", settings.marginTopDp, 4f, 0f..80f, format = { "${it.toInt()}dp" }) { viewModel.setMarginTopDp(it) }
            LabeledStepper("아래", settings.marginBottomDp, 4f, 0f..80f, format = { "${it.toInt()}dp" }) { viewModel.setMarginBottomDp(it) }

            SectionDivider()
            Text("테마", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemePreset.LIGHT to "라이트", ThemePreset.DARK to "다크", ThemePreset.SEPIA to "세피아").forEach { (preset, label) ->
                    FilterChip(
                        selected = settings.themePreset == preset,
                        onClick = { viewModel.setThemePreset(preset) },
                        label = { Text(label) },
                    )
                }
            }

            SectionDivider()
            Text("넘김 방식", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.pageTurnMode == PageTurnMode.HORIZONTAL_PAGE,
                    onClick = { viewModel.setPageTurnMode(PageTurnMode.HORIZONTAL_PAGE) },
                    label = { Text("페이지 넘김") },
                )
                FilterChip(
                    selected = settings.pageTurnMode == PageTurnMode.VERTICAL_SCROLL,
                    onClick = { viewModel.setPageTurnMode(PageTurnMode.VERTICAL_SCROLL) },
                    label = { Text("스크롤") },
                )
            }

            SectionDivider()
            Text("전환 애니메이션", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    PageTransitionAnimation.NONE to "없음",
                    PageTransitionAnimation.SLIDE to "슬라이드",
                    PageTransitionAnimation.COVER to "덮기",
                ).forEach { (animation, label) ->
                    FilterChip(
                        selected = settings.pageTransitionAnimation == animation,
                        onClick = { viewModel.setPageTransitionAnimation(animation) },
                        label = { Text(label) },
                    )
                }
            }

            SectionDivider()
            Text("페이지 넘김 옵션", style = MaterialTheme.typography.titleMedium)
            Text("터치 (좌/우 탭)", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.touchTurnMode == TouchTurnMode.STANDARD,
                    onClick = { viewModel.setTouchTurnMode(TouchTurnMode.STANDARD) },
                    label = { Text("왼쪽 이전 · 오른쪽 다음") },
                )
                FilterChip(
                    selected = settings.touchTurnMode == TouchTurnMode.BOTH_NEXT,
                    onClick = { viewModel.setTouchTurnMode(TouchTurnMode.BOTH_NEXT) },
                    label = { Text("양쪽 다 다음") },
                )
            }
            Text("스와이프 (좌우 밀기)", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.swipeTurnMode == SwipeTurnMode.STANDARD,
                    onClick = { viewModel.setSwipeTurnMode(SwipeTurnMode.STANDARD) },
                    label = { Text("← 다음 · → 이전") },
                )
                FilterChip(
                    selected = settings.swipeTurnMode == SwipeTurnMode.BOTH_NEXT,
                    onClick = { viewModel.setSwipeTurnMode(SwipeTurnMode.BOTH_NEXT) },
                    label = { Text("양방향 다 다음") },
                )
            }

            SectionDivider()
            Text("줄바꿈", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.lineBreakMode == LineBreakMode.PRESERVE,
                    onClick = { viewModel.setLineBreakMode(LineBreakMode.PRESERVE) },
                    label = { Text("원문 유지") },
                )
                FilterChip(
                    selected = settings.lineBreakMode == LineBreakMode.REFLOW,
                    onClick = { viewModel.setLineBreakMode(LineBreakMode.REFLOW) },
                    label = { Text("문단 재구성") },
                )
            }

            SectionDivider()
            Text("챕터 점프", style = MaterialTheme.typography.titleMedium)
            SwitchRow("켜짐", settings.chapterJumpEnabled) { viewModel.setChapterJumpEnabled(it) }
            LabeledStepper("등분 수", settings.chapterJumpDivisions.toFloat(), 1f, 2f..10f, format = { "${it.toInt()}" }) {
                viewModel.setChapterJumpDivisions(it.toInt())
            }
            OutlinedButton(onClick = { showChapterPatterns = true }, modifier = Modifier.fillMaxWidth()) {
                Text("챕터 인식 패턴 설정")
            }

            SectionDivider()
            Text("화면", style = MaterialTheme.typography.titleMedium)
            SwitchRow("화면 꺼짐 방지", settings.keepScreenOnEnabled) { viewModel.setKeepScreenOnEnabled(it) }
            SwitchRow("볼륨키로 넘기기", settings.volumeKeyPagingEnabled) { viewModel.setVolumeKeyPagingEnabled(it) }
            SwitchRow("밝기 직접 조절", settings.brightnessOverrideEnabled) { viewModel.setBrightnessOverrideEnabled(it) }
            if (settings.brightnessOverrideEnabled) {
                LabeledStepper("밝기", settings.brightnessValue, 0.05f, 0.05f..1f, format = { "${(it * 100).toInt()}%" }) {
                    viewModel.setBrightnessValue(it)
                }
            }
            Text("화면 방향", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(OrientationLock.AUTO to "자동", OrientationLock.PORTRAIT to "세로", OrientationLock.LANDSCAPE to "가로").forEach { (lock, label) ->
                    FilterChip(
                        selected = settings.orientationLock == lock,
                        onClick = { viewModel.setOrientationLock(lock) },
                        label = { Text(label) },
                    )
                }
            }

            SectionDivider()
            Text("자동 넘김 / TTS", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AutoAdvanceMode.OFF to "끄기",
                    AutoAdvanceMode.TIMER to "타이머",
                    AutoAdvanceMode.TTS to "TTS",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.autoAdvanceMode == mode,
                        onClick = { viewModel.setAutoAdvanceMode(mode) },
                        label = { Text(label) },
                    )
                }
            }
            if (settings.autoAdvanceMode == AutoAdvanceMode.TIMER) {
                LabeledStepper("간격", settings.autoPageTurnIntervalSeconds.toFloat(), 5f, 3f..60f, format = { "${it.toInt()}초" }) {
                    viewModel.setAutoPageTurnIntervalSeconds(it.toInt())
                }
            }

            SectionDivider()
            Text("VSCode 읽기 위치 동기화", style = MaterialTheme.typography.titleMedium)
            Text(
                "PC(VSCode)에서 만든 공유 시크릿을 붙여넣고 연결 테스트를 눌러 확인하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SyncSettingField("공유 시크릿", sharedSecretDraft, isSecret = true) {
                sharedSecretDraft = it
                syncTestFailed = false
            }
            val isVerified = sharedSecretDraft.isNotBlank() && sharedSecretDraft == settings.supabaseVerifiedSecret
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        testingSync = true
                        syncTestFailed = false
                        coroutineScope.launch {
                            val success = viewModel.testSupabaseConnection(sharedSecretDraft)
                            testingSync = false
                            syncTestFailed = !success
                        }
                    },
                    enabled = sharedSecretDraft.isNotBlank() && !testingSync,
                ) {
                    if (testingSync) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("연결 테스트")
                    }
                }
                when {
                    isVerified -> {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32))
                        Text("연결됨", color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodyMedium)
                    }
                    syncTestFailed -> Text(
                        "연결 실패 — 시크릿을 확인하세요",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showFontPicker) {
        FontPickerSheet(viewModel = viewModel, settings = settings, onDismiss = { showFontPicker = false })
    }
    if (showChapterPatterns) {
        ChapterPatternSheet(viewModel = viewModel, settings = settings, onDismiss = { showChapterPatterns = false })
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
}

/**
 * 순수 컨트롤드 필드 — DataStore에는 바로 안 쓰고, 상위(QuickSettingsSheet)가 들고 있는 로컬 초안
 * 상태만 갱신한다. 실제 커밋은 시트가 닫힐 때 한 번에 일어난다(매 키 입력마다 DataStore 왕복 쓰기가
 * 생기면 느리고, 그 응답으로 [settings]가 재emit될 때마다 입력 중인 커서 위치가 흐트러질 수 있어서다).
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
    // Row 전체를 toggleable로 감싼다 — 터치 영역이 스위치 썸(thumb)만큼 작은 걸 라벨까지 넓히고
    // (Material 접근성 가이드), 그 결과로 라벨+스위치가 시맨틱 트리에서 하나로 병합돼(mergeDescendants)
    // 라벨 텍스트만으로도 이 스위치를 정확히 찾아 조작할 수 있다 — 이 시트에 스위치가 여러 개라
    // Row가 시맨틱 경계를 안 만들면(Row 자체는 기본적으로 그렇다) 전부 같은 부모 아래 형제로 평탄화돼,
    // 라벨 텍스트 하나로 스위치를 구분할 방법이 없었다.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** +/- 버튼으로 값을 조절하는 수치 컨트롤 — 슬라이더보다 손가락으로 정확히 집기 쉽다. */
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
            Icon(Icons.Default.Remove, contentDescription = "$label 감소")
        }
        Text(format(value), modifier = Modifier.widthIn(min = 48.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { onValueChange((value + step).coerceIn(range)) }, enabled = value < range.endInclusive) {
            Icon(Icons.Default.Add, contentDescription = "$label 증가")
        }
    }
}

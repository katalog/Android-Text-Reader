package com.moonkata.textreader.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.data.datastore.ReaderSettings
import kotlinx.coroutines.launch

/**
 * PC 트레이 서버 파일 동기화 연결 설정 — .docs/PC_SYNC_SERVER_PLAN.md §4. Supabase 공유 시크릿과
 * 완전히 같은 패턴(호스트 + 공유 시크릿, "테스트 성공 시점 값과 비교"로 검증 상태 판단) — 실제 계정
 * 자격증명은 필요 없다(SMB를 버리고 우리가 프로토콜을 직접 통제하는 방식으로 바뀌었기 때문).
 *
 * 입력 필드는 QuickSettingsSheet의 Supabase 시크릿과 동일한 방식 — 매 키 입력마다 DataStore에 쓰지
 * 않고 로컬 초안 상태로만 들고 있다가, 시트가 닫히는 시점(onDismissRequest)에 한 번에 커밋한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcSyncSheet(viewModel: LibraryViewModel, settings: ReaderSettings, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    var hostDraft by remember { mutableStateOf(settings.pcSyncHost) }
    var secretDraft by remember { mutableStateOf(settings.pcSyncSecret) }

    var scanning by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testFailed by remember { mutableStateOf(false) }

    val dismissAndCommit: () -> Unit = {
        if (hostDraft != settings.pcSyncHost || secretDraft != settings.pcSyncSecret) {
            viewModel.updatePcSyncConnectionDraft(hostDraft, secretDraft)
        }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = dismissAndCommit, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("PC 파일 동기화", style = MaterialTheme.typography.titleMedium)
            Text(
                "PC에서 moonkata-sync-server를 실행하면 공유 시크릿이 표시됩니다 — 그 값을 아래에 " +
                    "붙여넣고 PC를 찾아 연결 테스트를 해보세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = hostDraft,
                onValueChange = { hostDraft = it; testFailed = false },
                label = { Text("PC 주소 (컴퓨터 이름 또는 IP)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedButton(
                onClick = {
                    scanning = true
                    scanResults = null
                    coroutineScope.launch {
                        scanResults = viewModel.scanForPcSyncHosts()
                        scanning = false
                    }
                },
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("PC 찾기")
                }
            }
            scanResults?.let { results ->
                if (results.isEmpty()) {
                    Text(
                        "같은 네트워크에서 PC 서버를 찾지 못했습니다 — moonkata-sync-server가 실행 중인지 확인하거나 주소를 직접 입력하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height((results.size.coerceAtMost(4) * 48).dp)) {
                        items(results) { ip ->
                            Text(
                                ip,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { hostDraft = ip; testFailed = false }
                                    .padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = secretDraft,
                onValueChange = { secretDraft = it; testFailed = false },
                label = { Text("공유 시크릿") },
                supportingText = { Text("PC의 moonkata-sync-server 창에 표시된 값을 그대로 붙여넣으세요") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )

            val isVerified = hostDraft.isNotBlank() && secretDraft.isNotBlank() &&
                hostDraft == settings.pcSyncVerifiedHost &&
                secretDraft == settings.pcSyncVerifiedSecret

            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        testing = true
                        testFailed = false
                        coroutineScope.launch {
                            val success = viewModel.testPcSyncConnection(hostDraft, secretDraft)
                            testing = false
                            testFailed = !success
                        }
                    },
                    enabled = hostDraft.isNotBlank() && secretDraft.isNotBlank() && !testing,
                ) {
                    if (testing) {
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
                    testFailed -> Text(
                        "연결 실패 — 주소/시크릿을 확인하세요",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("지금 동기화", style = MaterialTheme.typography.titleMedium)
            val syncState by viewModel.pcSyncState.collectAsState()
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.syncFromPc() },
                enabled = isVerified && !syncState.isSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (syncState.isSyncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("지금 동기화")
                }
            }
            if (!isVerified) {
                Text(
                    "연결 테스트를 먼저 통과해야 동기화할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            syncState.progress?.let { progress ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (progress.total > 0) progress.completed / progress.total.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${progress.completed} / ${progress.total} — ${progress.currentRelativePath}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            syncState.result?.let { result ->
                Text(
                    "받음 ${result.downloaded} · 갱신 ${result.updated} · 삭제 ${result.deleted}" +
                        if (result.failed > 0) " · 실패 ${result.failed}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.failed > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            syncState.errorMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = dismissAndCommit, modifier = Modifier.fillMaxWidth()) {
                Text("닫기")
            }
        }
    }
}

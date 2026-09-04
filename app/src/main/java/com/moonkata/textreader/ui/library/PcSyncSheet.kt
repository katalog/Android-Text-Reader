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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.R
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.sync.QrPairingPayload
import com.moonkata.textreader.ui.qr.QrScannerDialog
import kotlinx.coroutines.launch

/**
 * PC tray server file-sync connection settings — .docs/PC_SYNC_SERVER_PLAN.md §4. The exact same
 * pattern as the Supabase shared secret (host + shared secret, verified state judged by "compare
 * against the value at the moment the test last succeeded") — no real account credentials needed
 * (since we dropped SMB for a protocol we control directly).
 *
 * The input fields work the same way as the Supabase secret in QuickSettingsSheet — not written to
 * DataStore on every keystroke, only held as local draft state, committed once at the point the sheet
 * closes (onDismissRequest).
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
    var showQrScanner by remember { mutableStateOf(false) }

    val dismissAndCommit: () -> Unit = {
        if (hostDraft != settings.pcSyncHost || secretDraft != settings.pcSyncSecret) {
            viewModel.updatePcSyncConnectionDraft(hostDraft, secretDraft)
        }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = dismissAndCommit, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.pc_sync_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.pc_sync_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showQrScanner = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pc_sync_connect_via_qr))
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = hostDraft,
                onValueChange = { hostDraft = it; testFailed = false },
                label = { Text(stringResource(R.string.pc_sync_host_label)) },
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
                    Text(stringResource(R.string.pc_sync_find_pc))
                }
            }
            scanResults?.let { results ->
                if (results.isEmpty()) {
                    Text(
                        stringResource(R.string.pc_sync_none_found),
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
                label = { Text(stringResource(R.string.pc_sync_shared_secret)) },
                supportingText = { Text(stringResource(R.string.pc_sync_secret_hint)) },
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
                        Text(stringResource(R.string.pc_sync_test_connection))
                    }
                }
                when {
                    isVerified -> {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32))
                        Text(stringResource(R.string.pc_sync_connected), color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodyMedium)
                    }
                    testFailed -> Text(
                        stringResource(R.string.pc_sync_connection_failed, viewModel.lastPcSyncTestError() ?: stringResource(R.string.pc_sync_check_host_secret)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.pc_sync_now), style = MaterialTheme.typography.titleMedium)
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
                    Text(stringResource(R.string.pc_sync_now))
                }
            }
            if (!isVerified) {
                Text(
                    stringResource(R.string.pc_sync_test_first),
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
                    stringResource(R.string.pc_sync_progress, progress.completed, progress.total, progress.currentRelativePath),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            syncState.result?.let { result ->
                Text(
                    stringResource(R.string.pc_sync_result, result.downloaded, result.updated, result.deleted) +
                        if (result.failed > 0) stringResource(R.string.pc_sync_result_failed_suffix, result.failed) else "",
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
                Text(stringResource(R.string.pc_sync_close))
            }
        }
    }

    if (showQrScanner) {
        // On a successful QR scan, fill in the host/secret and immediately auto-run the connection
        // test with pinned TLS — this skips both the "Find PC" subnet scan and typing the secret
        // (the QR already has everything). An invalid QR or denied permission is handled by
        // QrScannerDialog itself via onDismiss, so nothing happens here in that case.
        QrScannerDialog(
            onResult = { payload ->
                showQrScanner = false
                if (payload is QrPairingPayload.PcSync) {
                    hostDraft = payload.host
                    secretDraft = payload.secret
                    testFailed = false
                    testing = true
                    coroutineScope.launch {
                        val success = viewModel.testPcSyncConnectionWithFingerprint(
                            payload.host,
                            payload.secret,
                            payload.fingerprint,
                        )
                        testing = false
                        testFailed = !success
                    }
                }
            },
            onDismiss = { showQrScanner = false },
        )
    }
}

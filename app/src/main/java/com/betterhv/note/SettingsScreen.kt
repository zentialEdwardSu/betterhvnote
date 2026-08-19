package com.betterhv.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betterhv.note.storage.StartupBehavior
import com.betterhv.transfer.core.PairedDevice
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    debugMode: Boolean,
    startupBehavior: StartupBehavior,
    pairedClients: List<PairedDevice>,
    onlineClients: List<PhoneTransferClient.AvailableNoteLink>,
    pairingCandidates: List<PhoneTransferClient.PairingCandidate>,
    pairingScanActive: Boolean,
    transferStatus: String,
    transferPermissionsGranted: Boolean,
    skipSourceSelectionWhenQueueAvailable: Boolean,
    autoCreatePageOnNextAtEnd: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    onStartupBehaviorChange: (StartupBehavior) -> Unit,
    onSkipSourceSelectionChange: (Boolean) -> Unit,
    onAutoCreatePageOnNextAtEndChange: (Boolean) -> Unit,
    onScanClients: () -> Unit,
    onPairClient: (PhoneTransferClient.PairingCandidate, String) -> Unit,
    onRenameClient: (String, String) -> Unit,
    onUnpairClient: (String) -> Unit,
    onTransferPermissions: () -> Unit,
    onRefreshTransferStatus: () -> Unit,
    onClose: () -> Unit
) {
    var pairingCode by remember { mutableStateOf("") }
    var selectedCandidateId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<PairedDevice?>(null) }
    val selectedCandidate = pairingCandidates.firstOrNull { it.deviceId == selectedCandidateId }

    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("设置", fontSize = 26.sp)
            }
            SettingRow("调试模式", onClick = { onDebugModeChange(!debugMode) }) {
                Switch(checked = debugMode, onCheckedChange = onDebugModeChange)
            }
            SettingRow("队列有内容时直接从 NoteLink 获取", onClick = {
                onSkipSourceSelectionChange(!skipSourceSelectionWhenQueueAvailable)
            }) {
                Switch(
                    checked = skipSourceSelectionWhenQueueAvailable,
                    onCheckedChange = onSkipSourceSelectionChange
                )
            }
            SettingRow("末页按下一页时自动新增页面", onClick = {
                onAutoCreatePageOnNextAtEndChange(!autoCreatePageOnNextAtEnd)
            }) {
                Switch(
                    checked = autoCreatePageOnNextAtEnd,
                    onCheckedChange = onAutoCreatePageOnNextAtEndChange
                )
            }

            Text("NoteLink 客户端", modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            Text(transferStatus, modifier = Modifier.padding(bottom = 8.dp))
            if (pairedClients.isEmpty()) Text("尚未配对")
            pairedClients.forEach { client ->
                val online = onlineClients.firstOrNull { it.client.id == client.id }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(client.name, fontSize = 18.sp)
                        Text(
                            if (client.legacy) "旧配对，连接后自动识别" else client.id,
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                        Text(
                            if (online == null) "离线"
                            else "在线 · ${online.imageCount} 张图片 · ${online.textCount} 段文字",
                            fontSize = 12.sp,
                            color = if (online == null) Color.DarkGray else Color(0xFF246B3A)
                        )
                        Text(
                            "最近使用 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(client.lastUsedAt))}",
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                    }
                    OutlinedButton(onClick = { renameTarget = client }) { Text("重命名") }
                    OutlinedButton(onClick = { onUnpairClient(client.id) }) { Text("移除") }
                }
                HorizontalDivider()
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onScanClients, enabled = !pairingScanActive) {
                    Text(if (pairingScanActive) "正在扫描" else "扫描新客户端")
                }
                if (!transferPermissionsGranted) {
                    OutlinedButton(onClick = onTransferPermissions) { Text("授予权限") }
                }
                OutlinedButton(onClick = onRefreshTransferStatus) { Text("刷新状态") }
            }

            pairingCandidates.forEach { candidate ->
                SettingRow(candidate.name, onClick = { selectedCandidateId = candidate.deviceId }) {
                    RadioButton(
                        selected = candidate.deviceId == selectedCandidateId,
                        onClick = { selectedCandidateId = candidate.deviceId }
                    )
                }
            }
            if (pairingCandidates.isNotEmpty()) {
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("所选 NoteLink 显示的六位配对码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        selectedCandidate?.let { onPairClient(it, pairingCode) }
                        pairingCode = ""
                        selectedCandidateId = null
                    },
                    enabled = selectedCandidate != null && pairingCode.length == 6,
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("完成配对") }
            }

            Text("重新进入应用时", modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            StartupBehavior.entries.forEach { behavior ->
                val label = when (behavior) {
                    StartupBehavior.WORKING_COPY -> "打开 Working Copy"
                    StartupBehavior.LAST_OPENED -> "打开上次使用的笔记本"
                }
                SettingRow(label, onClick = { onStartupBehaviorChange(behavior) }) {
                    RadioButton(
                        selected = startupBehavior == behavior,
                        onClick = { onStartupBehaviorChange(behavior) }
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名 NoteLink") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRenameClient(target.id, name)
                    renameTarget = null
                }, enabled = name.isNotBlank()) { Text("保存") }
            },
            dismissButton = {
                OutlinedButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    onClick: () -> Unit,
    control: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 18.sp)
        control()
    }
}

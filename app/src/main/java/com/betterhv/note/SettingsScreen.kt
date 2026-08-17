package com.betterhv.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
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

@Composable
fun SettingsScreen(
    debugMode: Boolean,
    startupBehavior: StartupBehavior,
    pairedPhoneName: String?,
    transferStatus: String,
    transferPermissionsGranted: Boolean,
    skipSourceSelectionWhenQueueAvailable: Boolean,
    autoCreatePageOnNextAtEnd: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    onStartupBehaviorChange: (StartupBehavior) -> Unit,
    onSkipSourceSelectionChange: (Boolean) -> Unit,
    onAutoCreatePageOnNextAtEndChange: (Boolean) -> Unit,
    onPairPhone: (String) -> Unit,
    onUnpairPhone: () -> Unit,
    onTransferPermissions: () -> Unit,
    onRefreshTransferStatus: () -> Unit,
    onClose: () -> Unit
) {
    var pairingCode by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("设置", fontSize = 26.sp)
            }
            SettingRow("调试模式", onClick = { onDebugModeChange(!debugMode) }) {
                Switch(checked = debugMode, onCheckedChange = onDebugModeChange)
            }
            SettingRow("队列有内容时直接从手机取", onClick = {
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
            Text("手机传输", modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            Text(pairedPhoneName?.let { "已配对：$it" } ?: "尚未配对")
            Text(transferStatus, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                label = { Text("手机显示的六位配对码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = { onPairPhone(pairingCode); pairingCode = "" },
                    enabled = pairingCode.length == 6
                ) { Text(if (pairedPhoneName == null) "完成配对" else "重新配对") }
                if (!transferPermissionsGranted) {
                    OutlinedButton(onClick = onTransferPermissions) { Text("授予权限") }
                }
                OutlinedButton(onClick = onRefreshTransferStatus) { Text("刷新状态") }
                if (pairedPhoneName != null) OutlinedButton(onClick = onUnpairPhone) { Text("取消配对") }
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

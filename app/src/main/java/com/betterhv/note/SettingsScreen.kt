package com.betterhv.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betterhv.note.storage.StartupBehavior
import com.betterhv.transfer.core.PairedDevice
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferLogEntry
import com.betterhv.transfer.core.TransferLogLevel
import com.betterhv.transfer.core.TransferModes
import com.betterhv.transfer.core.TransferSnapshot
import java.text.DateFormat
import java.util.Date

private enum class SettingsTab(val label: String) {
    GENERAL("常规"),
    TOOLBAR("工具栏"),
    SHORTCUTS("物理按键"),
    NOTELINK("NoteLink")
}

data class SettingsState(
    val debugMode: Boolean,
    val startupBehavior: StartupBehavior,
    val pairedClients: List<PairedDevice>,
    val onlineClients: List<PhoneTransferClient.AvailableNoteLink>,
    val pairingCandidates: List<PhoneTransferClient.PairingCandidate>,
    val pairingScanActive: Boolean,
    val pairingInProgress: Boolean,
    val transferStatus: String,
    val transferSnapshot: TransferSnapshot,
    val transferEvents: List<TransferLogEntry>,
    val transferEndpointName: String?,
    val transferPermissionsGranted: Boolean,
    val skipSourceSelectionWhenQueueAvailable: Boolean,
    val autoCreatePageOnNextAtEnd: Boolean,
    val showRecentTransferEvents: Boolean,
    val visibleToolbarItems: Set<ToolbarItem>,
    val shortcutBindings: HardwareShortcutBindings,
    val shortcutBindingRequest: Pair<ShortcutScene, HardwareKeyId>?,
)

data class SettingsActions(
    val onDebugModeChange: (Boolean) -> Unit,
    val onStartupBehaviorChange: (StartupBehavior) -> Unit,
    val onSkipSourceSelectionChange: (Boolean) -> Unit,
    val onAutoCreatePageOnNextAtEndChange: (Boolean) -> Unit,
    val onShowRecentTransferEventsChange: (Boolean) -> Unit,
    val onToolbarItemVisibilityChange: (ToolbarItem, Boolean) -> Unit,
    val onResetToolbarItems: () -> Unit,
    val onStartShortcutCapture: (ShortcutScene) -> Unit,
    val onShortcutBindingRequestConsumed: () -> Unit,
    val onShortcutBind: (ShortcutScene, HardwareKeyId, ShortcutAction?) -> Unit,
    val onShortcutSceneClear: (ShortcutScene) -> Unit,
    val onScanClients: () -> Unit,
    val onPairClient: (PhoneTransferClient.PairingCandidate, String) -> Unit,
    val onRenameClient: (String, String) -> Unit,
    val onUnpairClient: (String) -> Unit,
    val onTransferPermissions: () -> Unit,
    val onRefreshTransferStatus: () -> Unit,
    val onCancelTransfer: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
fun SettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
) {
    val debugMode = state.debugMode
    val startupBehavior = state.startupBehavior
    val pairedClients = state.pairedClients
    val onlineClients = state.onlineClients
    val pairingCandidates = state.pairingCandidates
    val pairingScanActive = state.pairingScanActive
    val pairingInProgress = state.pairingInProgress
    val transferStatus = state.transferStatus
    val transferSnapshot = state.transferSnapshot
    val transferEvents = state.transferEvents
    val transferEndpointName = state.transferEndpointName
    val transferPermissionsGranted = state.transferPermissionsGranted
    val skipSourceSelectionWhenQueueAvailable = state.skipSourceSelectionWhenQueueAvailable
    val autoCreatePageOnNextAtEnd = state.autoCreatePageOnNextAtEnd
    val showRecentTransferEvents = state.showRecentTransferEvents
    val visibleToolbarItems = state.visibleToolbarItems
    val shortcutBindings = state.shortcutBindings
    val shortcutBindingRequest = state.shortcutBindingRequest
    val onDebugModeChange = actions.onDebugModeChange
    val onStartupBehaviorChange = actions.onStartupBehaviorChange
    val onSkipSourceSelectionChange = actions.onSkipSourceSelectionChange
    val onAutoCreatePageOnNextAtEndChange = actions.onAutoCreatePageOnNextAtEndChange
    val onShowRecentTransferEventsChange = actions.onShowRecentTransferEventsChange
    val onToolbarItemVisibilityChange = actions.onToolbarItemVisibilityChange
    val onResetToolbarItems = actions.onResetToolbarItems
    val onStartShortcutCapture = actions.onStartShortcutCapture
    val onShortcutBindingRequestConsumed = actions.onShortcutBindingRequestConsumed
    val onShortcutBind = actions.onShortcutBind
    val onShortcutSceneClear = actions.onShortcutSceneClear
    val onScanClients = actions.onScanClients
    val onPairClient = actions.onPairClient
    val onRenameClient = actions.onRenameClient
    val onUnpairClient = actions.onUnpairClient
    val onTransferPermissions = actions.onTransferPermissions
    val onRefreshTransferStatus = actions.onRefreshTransferStatus
    val onCancelTransfer = actions.onCancelTransfer
    val onClose = actions.onClose
    var pairingCode by remember { mutableStateOf("") }
    var selectedCandidateId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<PairedDevice?>(null) }
    var shortcutScene by remember { mutableStateOf(ShortcutScene.EDITOR) }
    var shortcutKey by remember { mutableStateOf<HardwareKeyId?>(null) }
    var showOpenSourceLicenses by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.GENERAL) }
    val selectedCandidate = pairingCandidates.firstOrNull { it.deviceId == selectedCandidateId }
    LaunchedEffect(shortcutBindingRequest) {
        shortcutBindingRequest?.let { (scene, key) ->
            selectedTab = SettingsTab.SHORTCUTS
            shortcutScene = scene
            shortcutKey = key
            onShortcutBindingRequestConsumed()
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("设置", fontSize = 26.sp)
            }
            TabRow(selectedTabIndex = selectedTab.ordinal, containerColor = Color.White) {
                SettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label, fontSize = 16.sp) }
                    )
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (selectedTab) {
                    SettingsTab.GENERAL -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        SettingRow("调试模式", onClick = { onDebugModeChange(!debugMode) }) {
                            Switch(checked = debugMode, onCheckedChange = onDebugModeChange)
                        }
                        SettingRow("队列有内容时直接从 NoteLink 获取", onClick = {
                            onSkipSourceSelectionChange(!skipSourceSelectionWhenQueueAvailable)
                        }) {
                            Switch(skipSourceSelectionWhenQueueAvailable, onSkipSourceSelectionChange)
                        }
                        SettingRow("末页按下一页时自动新增页面", onClick = {
                            onAutoCreatePageOnNextAtEndChange(!autoCreatePageOnNextAtEnd)
                        }) {
                            Switch(autoCreatePageOnNextAtEnd, onAutoCreatePageOnNextAtEndChange)
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
                        BuildInfoRow("Build version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        BuildInfoRow(
                            "Build time",
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                                .format(Date(BuildConfig.BUILD_TIME_EPOCH_MILLIS))
                        )
                        SettingRow("开源许可", onClick = { showOpenSourceLicenses = true }) {
                            Text("AGPL-3.0-or-later", color = Color.DarkGray)
                        }
                    }

                    SettingsTab.TOOLBAR -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        Text("工具栏显示", fontSize = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
                        ToolbarItem.entries.filterNot { it == ToolbarItem.MENU }.chunked(2).forEach { items ->
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items.forEach { item ->
                                    val visible = item in visibleToolbarItems
                                    GridSettingCell(item.label, Modifier.weight(1f), {
                                        onToolbarItemVisibilityChange(item, !visible)
                                    }) {
                                        Switch(visible, { onToolbarItemVisibilityChange(item, it) })
                                    }
                                }
                                if (items.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Text(
                            "拖动把手和 Menu 始终显示；关闭的工具会自动进入 Menu。",
                            color = Color.DarkGray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        OutlinedButton(onClick = onResetToolbarItems, modifier = Modifier.padding(top = 12.dp)) {
                            Text("恢复默认显示")
                        }
                    }

                    SettingsTab.SHORTCUTS -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShortcutScene.entries.forEach { scene ->
                                OutlinedButton(onClick = { shortcutScene = scene }) {
                                    Text(if (scene == shortcutScene) "● ${scene.label}" else scene.label)
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { onStartShortcutCapture(shortcutScene) }) { Text("按实体键识别") }
                            OutlinedButton(onClick = { onShortcutSceneClear(shortcutScene) }) { Text("清空本场景") }
                        }
                        HardwareKeyId.entries.chunked(2).forEach { keys ->
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                keys.forEach { key ->
                                    val action = shortcutBindings.action(shortcutScene, key)
                                    GridSettingCell("K${key.number}", Modifier.weight(1f), { shortcutKey = key }) {
                                        Text(action?.label ?: "未绑定", color = Color.DarkGray, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }

                    SettingsTab.NOTELINK -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        Text("NoteLink 客户端", fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
                        Text(transferStatus, modifier = Modifier.padding(bottom = 8.dp))
                        SettingRow("显示最近传输事件", onClick = {
                            onShowRecentTransferEventsChange(!showRecentTransferEvents)
                        }) {
                            Switch(showRecentTransferEvents, onShowRecentTransferEventsChange)
                        }
                        TransferDiagnostics(
                            transferSnapshot, transferEvents, transferEndpointName,
                            showRecentTransferEvents, onCancelTransfer
                        )
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
                                    Text(if (client.legacy) "旧配对，连接后自动识别" else client.id, fontSize = 12.sp, color = Color.DarkGray)
                                    Text(
                                        if (online == null) "离线" else "在线 · ${online.imageCount} 张图片 · ${online.textCount} 段文字 · ${online.pdfCount} 份 PDF",
                                        fontSize = 12.sp,
                                        color = if (online == null) Color.DarkGray else Color(0xFF246B3A)
                                    )
                                    Text(
                                        "最近使用 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(client.lastUsedAt))}",
                                        fontSize = 12.sp, color = Color.DarkGray
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
                            Button(onClick = onScanClients, enabled = !pairingScanActive && !pairingInProgress) {
                                Text(if (pairingScanActive) "正在扫描" else "扫描新客户端")
                            }
                            if (!transferPermissionsGranted) OutlinedButton(onClick = onTransferPermissions) { Text("授予权限") }
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
                                pairingCode,
                                { pairingCode = it.filter(Char::isDigit).take(6) },
                                label = { Text("所选 NoteLink 显示的六位配对码") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            Button(
                                onClick = { selectedCandidate?.let { onPairClient(it, pairingCode) } },
                                enabled = selectedCandidate != null && pairingCode.length == 6 && !pairingInProgress,
                                modifier = Modifier.padding(top = 8.dp)
                            ) { Text(if (pairingInProgress) "正在配对" else "完成配对") }
                        }
                    }
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

    shortcutKey?.let { key ->
        AlertDialog(
            onDismissRequest = { shortcutKey = null },
            title = { Text("${shortcutScene.label} · K${key.number}") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    SettingRow("未绑定", onClick = {
                        onShortcutBind(shortcutScene, key, null)
                        shortcutKey = null
                    }) { RadioButton(selected = shortcutBindings.action(shortcutScene, key) == null, onClick = null) }
                    ShortcutAction.forScene(shortcutScene).forEach { action ->
                        SettingRow(action.label, onClick = {
                            onShortcutBind(shortcutScene, key, action)
                            shortcutKey = null
                        }) {
                            RadioButton(
                                selected = shortcutBindings.action(shortcutScene, key) == action,
                                onClick = null
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { shortcutKey = null }) { Text("取消") }
            }
        )
    }

    if (showOpenSourceLicenses) {
        AlertDialog(
            onDismissRequest = { showOpenSourceLicenses = false },
            title = { Text("开源许可") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text("BetterHvNote · AGPL-3.0-or-later", fontSize = 18.sp)
                    Text(
                        "PDF Ink annotation 导出使用 MuPDF fitz 1.28.0，" +
                            "Copyright Artifex Software, Inc.，按 GNU AGPL v3 或更高版本授权。",
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        "对应源代码：\nhttps://github.com/zentialEdwardSu/betterhvnote\n\n" +
                            "完整许可：\nhttps://www.gnu.org/licenses/agpl-3.0.txt",
                        color = Color.DarkGray,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        "汉王 ROM 接口由设备平台提供，声明仅用于编译，不随应用分发。",
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showOpenSourceLicenses = false }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    uriHandler.openUri("https://github.com/zentialEdwardSu/betterhvnote")
                }) { Text("查看源代码") }
            }
        )
    }
}

@Composable
private fun TransferDiagnostics(
    snapshot: TransferSnapshot,
    events: List<TransferLogEntry>,
    endpointName: String?,
    showRecentEvents: Boolean,
    onCancel: () -> Unit
) {
    if (snapshot.phase == TransferPhase.IDLE && (!showRecentEvents || events.isEmpty())) return
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            listOfNotNull("BLE", snapshot.phase.name.replace('_', ' '), snapshot.mode?.name, "SSID ${snapshot.ssidMatch.name}")
                .joinToString(" · ")
        )
        Text(
            "本端 ${TransferModes.describe(snapshot.localModes)} · 对端 ${TransferModes.describe(snapshot.remoteModes)}" +
                " · 尝试 ${snapshot.attempt} · fallback ${snapshot.fallbackCount}",
            fontSize = 12.sp,
            color = Color.DarkGray
        )
        snapshot.endpoint?.let {
            Text(
                "端点 ${endpointName ?: snapshot.deviceId ?: "未知设备"} · ${it.host}:${it.port}",
                fontSize = 12.sp,
                color = Color.DarkGray
            )
        }
        Text(
            "Wi-Fi Direct 组 ${if (snapshot.wifiDirectGroupReady) "已就绪" else "未就绪"}" +
                listOfNotNull(snapshot.deviceId?.let { " · 设备 $it" }, snapshot.operationId?.let { " · 操作 ${it.toString().take(8)}" })
                    .joinToString(""),
            fontSize = 12.sp,
            color = Color.DarkGray
        )
        if (snapshot.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { (snapshot.bytesTransferred.toFloat() / snapshot.totalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${noteFormatBytes(snapshot.bytesTransferred)} / ${noteFormatBytes(snapshot.totalBytes)}" +
                    " · 当前 ${noteFormatBytes(snapshot.bytesPerSecond)}/s" +
                    " · 平均 ${noteFormatBytes(snapshot.averageBytesPerSecond)}/s" +
                    (snapshot.etaMillis?.let { " · ETA ${noteFormatDuration(it)}" } ?: ""),
                fontSize = 12.sp,
                color = Color.DarkGray
            )
        }
        snapshot.fallbackReason?.let {
            Text("fallback ${it.code}: ${it.message}", fontSize = 12.sp, color = Color(0xFF6A4A00))
        }
        snapshot.lastFailure?.let {
            Text(
                "${it.code}: ${it.message} · ${if (it.recoverable) "可重试" else "不可重试"}",
                fontSize = 12.sp,
                color = Color(0xFF8A1C1C)
            )
        }
        if (snapshot.canCancel) OutlinedButton(onClick = onCancel) { Text("取消传输") }
        if (showRecentEvents && events.isNotEmpty()) {
            Text("最近传输事件", modifier = Modifier.padding(top = 4.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState())
            ) {
                events.asReversed().forEach { entry ->
                    Text(
                        "${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(entry.timestampMillis))} " +
                            "${entry.category} ${entry.detail}",
                        modifier = Modifier.fillMaxWidth().height(20.dp),
                        maxLines = 1,
                        fontSize = 11.sp,
                        color = if (entry.level == TransferLogLevel.ERROR) Color(0xFF8A1C1C) else Color.DarkGray
                    )
                }
            }

            HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 8.dp))
        }
    }
}

private fun noteFormatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GiB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MiB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun noteFormatDuration(millis: Long): String {
    val seconds = (millis / 1_000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
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

@Composable
private fun GridSettingCell(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
    control: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .border(1.dp, Color(0xFFB0B0B0))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 17.sp)
        control()
    }
}

@Composable
private fun BuildInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
        Text(value, fontSize = 14.sp, color = Color.DarkGray)
    }
}

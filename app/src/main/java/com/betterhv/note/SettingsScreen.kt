package com.betterhv.note

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betterhv.note.storage.StartupBehavior
import com.betterhv.transfer.core.PairedDevice
import com.betterhv.transfer.core.TransferLogEntry
import com.betterhv.transfer.core.TransferLogLevel
import com.betterhv.transfer.core.TransferModes
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferSnapshot
import com.betterhv.update.UpdateCheckState
import com.betterhv.update.UpdateUiState
import java.text.DateFormat
import java.util.Date

private enum class SettingsTab {
    GENERAL,
    TOOLBAR,
    SHORTCUTS,
    NOTELINK;

    fun label(): String = when (this) {
        GENERAL -> noteText("常规", "General")
        TOOLBAR -> noteText("工具栏", "Toolbar")
        SHORTCUTS -> noteText("物理按键", "Hardware keys")
        NOTELINK -> "NoteLink"
    }
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
    val language: NoteLanguage,
    val visibleToolbarItems: Set<ToolbarItem>,
    val shortcutBindings: HardwareShortcutBindings,
    val shortcutBindingRequest: Pair<ShortcutScene, HardwareKeyId>?,
    val updateUiState: UpdateUiState,
)

data class SettingsActions(
    val onDebugModeChange: (Boolean) -> Unit,
    val onStartupBehaviorChange: (StartupBehavior) -> Unit,
    val onSkipSourceSelectionChange: (Boolean) -> Unit,
    val onAutoCreatePageOnNextAtEndChange: (Boolean) -> Unit,
    val onShowRecentTransferEventsChange: (Boolean) -> Unit,
    val onLanguageChange: (NoteLanguage) -> Unit,
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
    val onCheckUpdate: () -> Unit,
    val onOpenRelease: (String) -> Unit,
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
    val language = state.language
    val visibleToolbarItems = state.visibleToolbarItems
    val shortcutBindings = state.shortcutBindings
    val shortcutBindingRequest = state.shortcutBindingRequest
    val updateUiState = state.updateUiState
    val onDebugModeChange = actions.onDebugModeChange
    val onStartupBehaviorChange = actions.onStartupBehaviorChange
    val onSkipSourceSelectionChange = actions.onSkipSourceSelectionChange
    val onAutoCreatePageOnNextAtEndChange = actions.onAutoCreatePageOnNextAtEndChange
    val onShowRecentTransferEventsChange = actions.onShowRecentTransferEventsChange
    val onLanguageChange = actions.onLanguageChange
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
    val onCheckUpdate = actions.onCheckUpdate
    val onOpenRelease = actions.onOpenRelease
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = noteText("返回", "Back"))
                }
                Text(noteText("设置", "Settings"), fontSize = 26.sp)
            }
            TabRow(selectedTabIndex = selectedTab.ordinal, containerColor = Color.White) {
                SettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label(), fontSize = 16.sp) }
                    )
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (selectedTab) {
                    SettingsTab.GENERAL -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        Text(noteText("语言", "Language"), modifier = Modifier.padding(bottom = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NoteLanguage.entries.forEach { option ->
                                OutlinedButton(onClick = { onLanguageChange(option) }) {
                                    Text((if (option == language) "✓ " else "") + option.displayName())
                                }
                            }
                        }
                        SettingRow(noteText("调试模式", "Debug mode"), onClick = { onDebugModeChange(!debugMode) }) {
                            Switch(checked = debugMode, onCheckedChange = onDebugModeChange)
                        }
                        SettingRow(noteText("队列有内容时直接从 NoteLink 获取", "Get directly from NoteLink when its queue is not empty"), onClick = {
                            onSkipSourceSelectionChange(!skipSourceSelectionWhenQueueAvailable)
                        }) {
                            Switch(skipSourceSelectionWhenQueueAvailable, onSkipSourceSelectionChange)
                        }
                        SettingRow(noteText("末页按下一页时自动新增页面", "Create a page when navigating past the last page"), onClick = {
                            onAutoCreatePageOnNextAtEndChange(!autoCreatePageOnNextAtEnd)
                        }) {
                            Switch(autoCreatePageOnNextAtEnd, onAutoCreatePageOnNextAtEndChange)
                        }
                        Text(noteText("重新进入应用时", "When reopening the app"), modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                        StartupBehavior.entries.forEach { behavior ->
                            val label = when (behavior) {
                                StartupBehavior.WORKING_COPY -> noteText("打开 Working Copy", "Open Working Copy")
                                StartupBehavior.LAST_OPENED -> noteText("打开上次使用的笔记本", "Open the last notebook")
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
                        UpdateSettingsSection(
                            currentVersion = BuildConfig.VERSION_NAME,
                            state = updateUiState,
                            onCheck = onCheckUpdate,
                            onOpenRelease = onOpenRelease,
                        )
                        SettingRow(noteText("开源许可", "Open-source licenses"), onClick = { showOpenSourceLicenses = true }) {
                            Text("AGPL-3.0-or-later", color = Color.DarkGray)
                        }
                    }

                    SettingsTab.TOOLBAR -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        Text(noteText("工具栏显示", "Toolbar items"), fontSize = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
                        ToolbarItem.entries.filterNot { it == ToolbarItem.MENU }.chunked(2).forEach { items ->
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items.forEach { item ->
                                    val visible = item in visibleToolbarItems
                                    GridSettingCell(item.localizedLabel(), Modifier.weight(1f), {
                                        onToolbarItemVisibilityChange(item, !visible)
                                    }) {
                                        Switch(visible, { onToolbarItemVisibilityChange(item, it) })
                                    }
                                }
                                if (items.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Text(
                            noteText(
                                "拖动把手和 Menu 始终显示；关闭的工具会自动进入 Menu。",
                                "The drag handle and Menu are always visible. Hidden tools move into Menu.",
                            ),
                            color = Color.DarkGray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        OutlinedButton(onClick = onResetToolbarItems, modifier = Modifier.padding(top = 12.dp)) {
                            Text(noteText("恢复默认显示", "Restore defaults"))
                        }
                    }

                    SettingsTab.SHORTCUTS -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShortcutScene.entries.forEach { scene ->
                                OutlinedButton(onClick = { shortcutScene = scene }) {
                                    Text((if (scene == shortcutScene) "● " else "") + scene.localizedLabel())
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { onStartShortcutCapture(shortcutScene) }) { Text(noteText("按实体键识别", "Identify hardware key")) }
                            OutlinedButton(onClick = { onShortcutSceneClear(shortcutScene) }) { Text(noteText("清空本场景", "Clear this context")) }
                        }
                        HardwareKeyId.entries.chunked(2).forEach { keys ->
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                keys.forEach { key ->
                                    val action = shortcutBindings.action(shortcutScene, key)
                                    GridSettingCell("K${key.number}", Modifier.weight(1f), { shortcutKey = key }) {
                                        Text(action?.localizedLabel() ?: noteText("未绑定", "Unassigned"), color = Color.DarkGray, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }

                    SettingsTab.NOTELINK -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        Text(noteText("NoteLink 客户端", "NoteLink clients"), fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
                        Text(transferStatus, modifier = Modifier.padding(bottom = 8.dp))
                        SettingRow(noteText("显示最近传输事件", "Show recent transfer events"), onClick = {
                            onShowRecentTransferEventsChange(!showRecentTransferEvents)
                        }) {
                            Switch(showRecentTransferEvents, onShowRecentTransferEventsChange)
                        }
                        TransferDiagnostics(
                            transferSnapshot, transferEvents, transferEndpointName,
                            showRecentTransferEvents, onCancelTransfer
                        )
                        if (pairedClients.isEmpty()) Text(noteText("尚未配对", "Not paired"))
                        pairedClients.forEach { client ->
                            val online = onlineClients.firstOrNull { it.client.id == client.id }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(client.name, fontSize = 18.sp)
                                    Text(if (client.legacy) noteText("旧配对，连接后自动识别", "Legacy pairing; identified on connection") else client.id, fontSize = 12.sp, color = Color.DarkGray)
                                    Text(
                                        if (online == null) noteText("离线", "Offline") else noteText(
                                            "在线 · ${online.imageCount} 张图片 · ${online.textCount} 段文字 · ${online.pdfCount} 份 PDF",
                                            "Online · ${online.imageCount} images · ${online.textCount} texts · ${online.pdfCount} PDFs",
                                        ),
                                        fontSize = 12.sp,
                                        color = if (online == null) Color.DarkGray else Color(0xFF246B3A)
                                    )
                                    Text(
                                        noteText("最近使用", "Last used") + " ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(client.lastUsedAt))}",
                                        fontSize = 12.sp, color = Color.DarkGray
                                    )
                                }
                                OutlinedButton(onClick = { renameTarget = client }) { Text(noteText("重命名", "Rename")) }
                                OutlinedButton(onClick = { onUnpairClient(client.id) }) { Text(noteText("移除", "Remove")) }
                            }
                            HorizontalDivider()
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = onScanClients, enabled = !pairingScanActive && !pairingInProgress) {
                                Text(if (pairingScanActive) noteText("正在扫描", "Scanning") else noteText("扫描新客户端", "Scan for clients"))
                            }
                            if (!transferPermissionsGranted) OutlinedButton(onClick = onTransferPermissions) { Text(noteText("授予权限", "Grant permission")) }
                            OutlinedButton(onClick = onRefreshTransferStatus) { Text(noteText("刷新状态", "Refresh status")) }
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
                                label = { Text(noteText("所选 NoteLink 显示的六位配对码", "Six-digit code shown by the selected NoteLink")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            Button(
                                onClick = { selectedCandidate?.let { onPairClient(it, pairingCode) } },
                                enabled = selectedCandidate != null && pairingCode.length == 6 && !pairingInProgress,
                                modifier = Modifier.padding(top = 8.dp)
                            ) { Text(if (pairingInProgress) noteText("正在配对", "Pairing") else noteText("完成配对", "Pair")) }
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
            title = { Text(noteText("重命名 NoteLink", "Rename NoteLink")) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(noteText("名称", "Name")) }
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRenameClient(target.id, name)
                    renameTarget = null
                }, enabled = name.isNotBlank()) { Text(noteText("保存", "Save")) }
            },
            dismissButton = {
                OutlinedButton(onClick = { renameTarget = null }) { Text(noteText("取消", "Cancel")) }
            }
        )
    }

    shortcutKey?.let { key ->
        AlertDialog(
            onDismissRequest = { shortcutKey = null },
            title = { Text("${shortcutScene.localizedLabel()} · K${key.number}") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    SettingRow(noteText("未绑定", "Unassigned"), onClick = {
                        onShortcutBind(shortcutScene, key, null)
                        shortcutKey = null
                    }) { RadioButton(selected = shortcutBindings.action(shortcutScene, key) == null, onClick = null) }
                    ShortcutAction.forScene(shortcutScene).forEach { action ->
                        SettingRow(action.localizedLabel(), onClick = {
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
                TextButton(onClick = { shortcutKey = null }) { Text(noteText("取消", "Cancel")) }
            }
        )
    }

    if (showOpenSourceLicenses) {
        AlertDialog(
            onDismissRequest = { showOpenSourceLicenses = false },
            title = { Text(noteText("开源许可", "Open-source licenses")) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text("BetterHvNote · AGPL-3.0-or-later", fontSize = 18.sp)
                    Text(
                        noteText(
                            "PDF Ink annotation 导出使用 MuPDF fitz 1.28.0，Copyright Artifex Software, Inc.，按 GNU AGPL v3 或更高版本授权。",
                            "PDF ink annotation export uses MuPDF fitz 1.28.0, Copyright Artifex Software, Inc., licensed under GNU AGPL v3 or later.",
                        ),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        noteText("对应源代码：", "Source code:") +
                            "\nhttps://github.com/zentialEdwardSu/betterhvnote\n\n" +
                            noteText("完整许可：", "Full license:") +
                            "\nhttps://www.gnu.org/licenses/agpl-3.0.txt",
                        color = Color.DarkGray,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        noteText(
                            "汉王 ROM 接口由设备平台提供，声明仅用于编译，不随应用分发。",
                            "Hanvon ROM interfaces are provided by the device platform. Declarations are compile-only and are not distributed with the app.",
                        ),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showOpenSourceLicenses = false }) { Text(noteText("关闭", "Close")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    uriHandler.openUri("https://github.com/zentialEdwardSu/betterhvnote")
                }) { Text(noteText("查看源代码", "View source")) }
            }
        )
    }
}

@Composable
private fun UpdateSettingsSection(
    currentVersion: String,
    state: UpdateUiState,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    val checkState = state.checkState
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(noteText("应用更新", "App updates"), fontSize = 18.sp)
        Text(noteText("当前版本 $currentVersion", "Current version $currentVersion"), color = Color.DarkGray, modifier = Modifier.padding(top = 4.dp))
        Text(
            when (checkState) {
                UpdateCheckState.Idle -> noteText("尚未检查", "Not checked yet")
                UpdateCheckState.Checking -> noteText("正在检查 GitHub Releases…", "Checking GitHub Releases...")
                is UpdateCheckState.UpToDate -> checkState.latestVersion?.let {
                    noteText("已是最新版本（${it.display}）", "Up to date (${it.display})")
                } ?: noteText("暂无可用正式版本", "No stable release available")
                is UpdateCheckState.UpdateAvailable ->
                    noteText("发现新版本 ${checkState.info.latestVersion.display}：${checkState.info.releaseTitle}", "New version ${checkState.info.latestVersion.display}: ${checkState.info.releaseTitle}")
                is UpdateCheckState.Failed -> if (state.manualErrorVisible) {
                    noteText("检查失败：${checkState.message}", "Check failed: ${checkState.message}")
                } else {
                    noteText("自动检查暂时不可用", "Automatic check is temporarily unavailable")
                }
            },
            color = if (checkState is UpdateCheckState.Failed && state.manualErrorVisible) {
                Color.Red
            } else {
                Color.DarkGray
            },
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheck, enabled = checkState !is UpdateCheckState.Checking) {
                Text(if (checkState is UpdateCheckState.Checking) noteText("检查中", "Checking") else noteText("检查更新", "Check for updates"))
            }
            (checkState as? UpdateCheckState.UpdateAvailable)?.let { available ->
                Button(onClick = { onOpenRelease(available.info.releaseUrl) }) { Text(noteText("查看 Release", "View release")) }
            }
        }
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
            "${noteText("本端", "Local")} ${TransferModes.describe(snapshot.localModes)} · " +
                "${noteText("对端", "Remote")} ${TransferModes.describe(snapshot.remoteModes)}" +
                " · ${noteText("尝试", "attempt")} ${snapshot.attempt} · fallback ${snapshot.fallbackCount}",
            fontSize = 12.sp,
            color = Color.DarkGray
        )
        snapshot.endpoint?.let {
            Text(
                "${noteText("端点", "Endpoint")} ${endpointName ?: snapshot.deviceId ?: noteText("未知设备", "Unknown device")} · ${it.host}:${it.port}",
                fontSize = 12.sp,
                color = Color.DarkGray
            )
        }
        Text(
            "Wi-Fi Direct ${noteText("组", "group")} ${if (snapshot.wifiDirectGroupReady) noteText("已就绪", "ready") else noteText("未就绪", "not ready")}" +
                listOfNotNull(snapshot.deviceId?.let { " · ${noteText("设备", "device")} $it" }, snapshot.operationId?.let { " · ${noteText("操作", "operation")} ${it.toString().take(8)}" })
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
                    " · ${noteText("当前", "current")} ${noteFormatBytes(snapshot.bytesPerSecond)}/s" +
                    " · ${noteText("平均", "average")} ${noteFormatBytes(snapshot.averageBytesPerSecond)}/s" +
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
                "${it.code}: ${it.message} · ${if (it.recoverable) noteText("可重试", "retryable") else noteText("不可重试", "not retryable")}",
                fontSize = 12.sp,
                color = Color(0xFF8A1C1C)
            )
        }
        if (snapshot.canCancel) OutlinedButton(onClick = onCancel) { Text(noteText("取消传输", "Cancel transfer")) }
        if (showRecentEvents && events.isNotEmpty()) {
            Text(noteText("最近传输事件", "Recent transfer events"), modifier = Modifier.padding(top = 4.dp))
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

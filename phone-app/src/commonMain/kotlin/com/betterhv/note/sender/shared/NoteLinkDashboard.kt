package com.betterhv.note.sender.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.isActiveTransferPhase
import com.betterhv.transfer.core.TransferLogEntry
import com.betterhv.transfer.core.TransferLogLevel
import com.betterhv.transfer.core.TransferModes
import com.betterhv.transfer.core.TransferSnapshot
import com.betterhv.update.UpdateCheckState
import com.betterhv.update.UpdateInfo
import com.betterhv.update.UpdateUiState
import java.text.DateFormat
import java.util.Date

@Suppress("MagicNumber")
private val NoteLinkColorScheme = lightColorScheme(
    primary = Color(0xFF45494A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3E5),
    onPrimaryContainer = Color(0xFF34393A),
    inversePrimary = Color(0xFFC4CBCD),
    secondary = Color(0xFF4FAF7C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F0E3),
    onSecondaryContainer = Color(0xFF173D2A),
    tertiary = Color(0xFF78642D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E5B7),
    onTertiaryContainer = Color(0xFF302500),
    background = Color(0xFFFCFBFA),
    onBackground = Color(0xFF202223),
    surface = Color(0xFFFCFBFA),
    onSurface = Color(0xFF202223),
    surfaceVariant = Color(0xFFF0EFEE),
    onSurfaceVariant = Color(0xFF535758),
    surfaceTint = Color(0xFF45494A),
    inverseSurface = Color(0xFF303233),
    inverseOnSurface = Color(0xFFF4F5F4),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF767A7B),
    outlineVariant = Color(0xFFDDDCDC),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFCFBFA),
    surfaceDim = Color(0xFFE5E4E3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF9F8),
    surfaceContainer = Color(0xFFF5F4F3),
    surfaceContainerHigh = Color(0xFFF1F0EF),
    surfaceContainerHighest = Color(0xFFF7F6F5),
)

enum class DashboardItemState { PENDING, TRANSFERRING, FAILED, COMPLETE }

data class DashboardItem(
    val id: String,
    val title: String,
    val detail: String,
    val state: DashboardItemState,
    val progress: Float? = null
)

data class DashboardPairedDevice(val id: String, val name: String)

data class DashboardState(
    val displayName: String = "NoteLink",
    val status: String = NoteLinkLanguage.SYSTEM.text("正在检查设备", "Checking device"),
    val statusDetail: String = "",
    val receiveEnabled: Boolean = true,
    val pairedDevices: List<DashboardPairedDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val pairingCode: String? = null,
    val queue: List<DashboardItem> = emptyList(),
    val inbox: List<DashboardItem> = emptyList(),
    val transfer: TransferSnapshot = TransferSnapshot(),
    val transferLog: List<TransferLogEntry> = emptyList(),
    val showRecentTransferEvents: Boolean = true,
    val language: NoteLinkLanguage = NoteLinkLanguage.SYSTEM,
    val notice: String? = null,
    val appVersion: String = "",
    val updateUiState: UpdateUiState = UpdateUiState(),
)

data class DashboardActions(
    val addFiles: () -> Unit,
    val addText: (String) -> Unit,
    val pasteClipboard: () -> Unit,
    val deleteQueueItem: (String) -> Unit,
    val openInboxItem: (String) -> Unit,
    val saveInboxItem: (String) -> Unit,
    val deleteInboxItem: (String) -> Unit,
    val setReceiveEnabled: (Boolean) -> Unit,
    val saveDisplayName: (String) -> Unit,
    val beginPairing: () -> Unit,
    val selectDevice: (String) -> Unit,
    val unpair: (String) -> Unit,
    val refreshStatus: () -> Unit,
    val cancelTransfer: () -> Unit,
    val setShowRecentTransferEvents: (Boolean) -> Unit,
    val setLanguage: (NoteLinkLanguage) -> Unit,
    val checkForUpdates: () -> Unit,
    val openRelease: (String) -> Unit,
    val dismissUpdate: () -> Unit,
    val dragInboxItem: @Composable (String) -> Modifier = { Modifier }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteLinkDashboard(state: DashboardState, actions: DashboardActions) {
    val t: (String, String) -> String = { zh, en -> state.language.text(zh, en) }
    var showSettings by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var textValue by remember { mutableStateOf(TextFieldValue()) }
    MaterialTheme(colorScheme = NoteLinkColorScheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (showSettings) t("设置", "Settings") else state.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        if (showSettings) {
                            IconButton(onClick = { showSettings = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, t("返回", "Back"))
                            }
                        }
                    },
                    actions = {
                        if (!showSettings) {
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, t("设置", "Settings"))
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (showSettings) {
                    SettingsPage(state, actions)
                } else {
                    StatusBand(state, actions)
                    state.updateUiState.visibleUpdate?.let { info ->
                        UpdateNoticeCard(
                            info = info,
                            language = state.language,
                            onOpenRelease = { actions.openRelease(info.releaseUrl) },
                            onDismiss = actions.dismissUpdate,
                        )
                    }
                    state.notice?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                    TransferPage(state, actions, onAddText = { textValue = TextFieldValue(); showTextDialog = true })
                }
            }
        }
        if (showTextDialog) {
            AlertDialog(
                onDismissRequest = { showTextDialog = false },
                title = { Text(t("添加文字", "Add text")) },
                text = {
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        actions.addText(textValue.text)
                        showTextDialog = false
                    }) { Text(t("加入队列", "Add to queue")) }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showTextDialog = false }) { Text(t("取消", "Cancel")) }
                }
            )
        }
    }
}

@Composable
private fun StatusBand(state: DashboardState, actions: DashboardActions) {
    val t: (String, String) -> String = { zh, en -> state.language.text(zh, en) }
    val selected = state.pairedDevices.firstOrNull { it.id == state.selectedDeviceId }
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.pairedDevices.isEmpty()) t("尚未配对", "Not paired")
                else t("已配对设备 · ${state.pairedDevices.size}", "Paired devices · ${state.pairedDevices.size}"),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { DeviceSelectorDropdown(state, actions) }
                IconButton(onClick = actions.refreshStatus) {
                    Icon(Icons.Default.Refresh, t("刷新连接状态", "Refresh connection"))
                }
            }
            Text(state.status)
            if (state.statusDetail.isNotBlank()) {
                Text(state.statusDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TransferStatus(
                state.transfer,
                state.transferLog,
                selected?.name,
                state.showRecentTransferEvents,
                state.language,
                actions.cancelTransfer
            )
            if (state.pairingCode != null) {
                Text(
                    t(
                        "在 N10Pro 的“设置 → 手机传输”中输入此六位配对码",
                        "Enter this six-digit code in Settings > NoteLink on the N10Pro",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(state.pairingCode, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
            if (state.pairedDevices.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.setReceiveEnabled(!state.receiveEnabled) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (state.receiveEnabled) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                        Text(
                            if (state.receiveEnabled) t("暂停接收", "Pause receiving")
                            else t("恢复接收", "Resume receiving"),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferStatus(
    snapshot: TransferSnapshot,
    log: List<TransferLogEntry>,
    endpointName: String?,
    showRecentEvents: Boolean,
    language: NoteLinkLanguage,
    onCancel: () -> Unit
) {
    val t: (String, String) -> String = { zh, en -> language.text(zh, en) }
    if (!snapshot.phase.isActiveTransferPhase && (!showRecentEvents || log.isEmpty())) return
    Text(t("传输状态", "Transfer status"), style = MaterialTheme.typography.titleSmall)
    Text(
        listOfNotNull("BLE", snapshot.phase.name.replace('_', ' '), snapshot.mode?.name, "SSID ${snapshot.ssidMatch.name}")
            .joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        "${t("本端", "Local")} ${TransferModes.describe(snapshot.localModes)} · " +
            "${t("对端", "Remote")} ${TransferModes.describe(snapshot.remoteModes)}" +
            " · ${t("尝试", "attempt")} ${snapshot.attempt} · fallback ${snapshot.fallbackCount}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    snapshot.endpoint?.let {
        Text(
            "${t("端点", "Endpoint")} ${endpointName ?: snapshot.deviceId ?: t("未知设备", "Unknown device")} · ${it.host}:${it.port}",
            style = MaterialTheme.typography.bodySmall
        )
    }
    Text(
        "Wi-Fi Direct ${t("组", "group")} ${if (snapshot.wifiDirectGroupReady) t("已就绪", "ready") else t("未就绪", "not ready")}" +
            listOfNotNull(
                snapshot.deviceId?.let { " · ${t("设备", "device")} $it" },
                snapshot.operationId?.let { " · ${t("操作", "operation")} ${it.toString().take(8)}" },
            ).joinToString(""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (snapshot.totalBytes > 0) {
        LinearProgressIndicator(
            progress = { (snapshot.bytesTransferred.toFloat() / snapshot.totalBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "${formatBytes(snapshot.bytesTransferred)} / ${formatBytes(snapshot.totalBytes)}" +
                " · ${t("当前", "current")} ${formatRate(snapshot.bytesPerSecond)}" +
                " · ${t("平均", "average")} ${formatRate(snapshot.averageBytesPerSecond)}" +
                (snapshot.etaMillis?.let { " · ETA ${formatDuration(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall
        )
    }
    snapshot.fallbackReason?.let {
        Text("fallback ${it.code}: ${it.message}", color = MaterialTheme.colorScheme.tertiary)
    }
    snapshot.lastFailure?.let {
        Text(
            "${it.code}: ${it.message} · ${if (it.recoverable) t("可重试", "retryable") else t("不可重试", "not retryable")}",
            color = MaterialTheme.colorScheme.error
        )
    }
    if (snapshot.canCancel) {
        OutlinedButton(onClick = onCancel) {
            Icon(Icons.Default.Cancel, null)
            Text(t("取消传输", "Cancel transfer"), modifier = Modifier.padding(start = 6.dp))
        }
    }
    if (showRecentEvents && log.isNotEmpty()) {
        Text(t("最近事件", "Recent events"), style = MaterialTheme.typography.labelLarge)
        Column(
            Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState())
        ) {
            log.asReversed().forEach { entry ->
                Text(
                    "${formatTime(entry.timestampMillis)} ${entry.category} ${entry.detail}",
                    modifier = Modifier.fillMaxWidth().height(20.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.level == TransferLogLevel.ERROR) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatTime(timestampMillis: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestampMillis))

private fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GiB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MiB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1_000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

@Composable
private fun TransferPage(state: DashboardState, actions: DashboardActions, onAddText: () -> Unit) {
    val t: (String, String) -> String = { zh, en -> state.language.text(zh, en) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(actions.addFiles) { Icon(Icons.Default.AddPhotoAlternate, null); Text(t("添加文件", "Add files"), Modifier.padding(start = 6.dp)) }
                OutlinedButton(onClick = onAddText) { Icon(Icons.Default.Edit, null); Text(t("添加文字", "Add text"), Modifier.padding(start = 6.dp)) }
                OutlinedButton(actions.pasteClipboard) { Icon(Icons.Default.ContentPaste, null); Text(t("从剪贴板粘贴", "Paste clipboard"), Modifier.padding(start = 6.dp)) }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TransferSection(t("发送队列 · ${state.queue.size} 项", "Send queue · ${state.queue.size}"), Modifier.weight(1f), state.queue, t("发送队列为空", "Send queue is empty")) { item ->
                        IconButton(onClick = { actions.deleteQueueItem(item.id) }) { Icon(Icons.Default.Delete, t("删除", "Delete")) }
                    }
                    TransferSection(t("收到的导出 · ${state.inbox.size} 项", "Received exports · ${state.inbox.size}"), Modifier.weight(1f), state.inbox, t("收件箱为空", "Inbox is empty"), actions.dragInboxItem) { item ->
                        InboxActions(item, actions)
                    }
                }
            } else {
                TransferSection(t("发送队列 · ${state.queue.size} 项", "Send queue · ${state.queue.size}"), Modifier.weight(1f), state.queue, t("发送队列为空", "Send queue is empty")) { item ->
                    IconButton(onClick = { actions.deleteQueueItem(item.id) }) { Icon(Icons.Default.Delete, t("删除", "Delete")) }
                }
                TransferSection(t("收到的导出 · ${state.inbox.size} 项", "Received exports · ${state.inbox.size}"), Modifier.weight(1f), state.inbox, t("收件箱为空", "Inbox is empty"), actions.dragInboxItem) { item ->
                    InboxActions(item, actions)
                }
            }
        }
    }
}

@Composable
private fun TransferSection(
    title: String,
    modifier: Modifier,
    items: List<DashboardItem>,
    emptyText: String,
    itemModifier: @Composable (String) -> Modifier = { Modifier },
    actions: @Composable RowScope.(DashboardItem) -> Unit
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        ItemList(items, emptyText, itemModifier, actions)
    }
}

@Composable
private fun InboxActions(item: DashboardItem, actions: DashboardActions) {
    IconButton(onClick = { actions.openInboxItem(item.id) }) { Icon(Icons.Default.FolderOpen, noteLinkText("打开", "Open")) }
    IconButton(onClick = { actions.saveInboxItem(item.id) }) { Icon(Icons.Default.Save, noteLinkText("另存为", "Save as")) }
    IconButton(onClick = { actions.deleteInboxItem(item.id) }) { Icon(Icons.Default.Delete, noteLinkText("删除", "Delete")) }
}

@Composable
private fun InboxPage(items: List<DashboardItem>, actions: DashboardActions) {
    ItemList(items, noteLinkText("收件箱为空", "Inbox is empty"), actions = { item ->
        IconButton(onClick = { actions.openInboxItem(item.id) }) { Icon(Icons.Default.FolderOpen, noteLinkText("打开", "Open")) }
        IconButton(onClick = { actions.saveInboxItem(item.id) }) { Icon(Icons.Default.Save, noteLinkText("另存为", "Save as")) }
        IconButton(onClick = { actions.deleteInboxItem(item.id) }) { Icon(Icons.Default.Delete, noteLinkText("删除", "Delete")) }
    })
}

@Composable
private fun ItemList(
    items: List<DashboardItem>,
    emptyText: String,
    itemModifier: @Composable (String) -> Modifier = { Modifier },
    actions: @Composable RowScope.(DashboardItem) -> Unit
) {
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = DashboardItem::id) { item ->
            Card(Modifier.then(itemModifier(item.id))) {
                ListItem(
                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Row(content = { actions(item) }) }
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun SettingsPage(state: DashboardState, actions: DashboardActions) {
    val t: (String, String) -> String = { zh, en -> state.language.text(zh, en) }
    var name by remember(state.displayName) { mutableStateOf(state.displayName) }
    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            OutlinedTextField(name, { name = it }, label = { Text(t("显示名称", "Display name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = { actions.saveDisplayName(name) }) { Text(t("保存名称", "Save name")) }
        }
        item { HorizontalDivider() }
        item {
            ListItem(
                headlineContent = { Text(t("显示最近传输事件", "Show recent transfer events")) },
                trailingContent = {
                    Switch(
                        checked = state.showRecentTransferEvents,
                        onCheckedChange = actions.setShowRecentTransferEvents
                    )
                }
            )
        }
        item { HorizontalDivider() }
        item {
            Column {
                Text(t("语言", "Language"), style = MaterialTheme.typography.titleMedium)
                NoteLinkLanguage.entries.forEach { language ->
                    TextButton(onClick = { actions.setLanguage(language) }) {
                        Text((if (language == state.language) "✓ " else "") + language.displayName())
                    }
                }
            }
        }
        item { HorizontalDivider() }
        item {
            UpdateSettingsCard(
                currentVersion = state.appVersion,
                state = state.updateUiState,
                language = state.language,
                onCheck = actions.checkForUpdates,
                onOpenRelease = actions.openRelease,
            )
        }
        item { HorizontalDivider() }
        item {
            Text(t("已配对设备", "Paired devices"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            DeviceSelectorDropdown(state, actions)
        }
    }
}

@Composable
private fun DeviceSelectorDropdown(state: DashboardState, actions: DashboardActions) {
    val t: (String, String) -> String = { zh, en -> state.language.text(zh, en) }
    var expanded by remember { mutableStateOf(false) }
    val selected = state.pairedDevices.firstOrNull { it.id == state.selectedDeviceId }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.name ?: t("选择设备", "Select device"), modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, t("展开设备列表", "Expand device list"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.pairedDevices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.name) },
                    onClick = {
                        expanded = false
                        actions.selectDevice(device.id)
                    },
                    leadingIcon = {
                        if (device.id == state.selectedDeviceId) Icon(Icons.Default.Check, t("当前设备", "Current device"))
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            expanded = false
                            actions.unpair(device.id)
                        }) { Icon(Icons.Default.Delete, t("解绑 ${device.name}", "Unpair ${device.name}")) }
                    },
                )
            }
            if (state.pairedDevices.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(t("新增设备", "Add device")) },
                onClick = {
                    expanded = false
                    actions.beginPairing()
                },
                leadingIcon = { Icon(Icons.Default.Add, null) },
            )
        }
    }
}

@Composable
private fun UpdateNoticeCard(
    info: UpdateInfo,
    language: NoteLinkLanguage,
    onOpenRelease: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t: (String, String) -> String = { zh, en -> language.text(zh, en) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("发现新版本 ${info.latestVersion.display}", "New version ${info.latestVersion.display}"), style = MaterialTheme.typography.titleMedium)
            Text(info.releaseTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenRelease) { Text(t("查看 Release", "View release")) }
                OutlinedButton(onClick = onDismiss) { Text(t("关闭", "Close")) }
            }
        }
    }
}

@Composable
private fun UpdateSettingsCard(
    currentVersion: String,
    state: UpdateUiState,
    language: NoteLinkLanguage,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    val t: (String, String) -> String = { zh, en -> language.text(zh, en) }
    val checkState = state.checkState
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("应用更新", "App updates"), style = MaterialTheme.typography.titleMedium)
        Text(t("当前版本 $currentVersion", "Current version $currentVersion"))
        Text(
            when (checkState) {
                UpdateCheckState.Idle -> t("尚未检查", "Not checked yet")
                UpdateCheckState.Checking -> t("正在检查 GitHub Releases…", "Checking GitHub Releases...")
                is UpdateCheckState.UpToDate -> checkState.latestVersion?.let {
                    t("已是最新版本（${it.display}）", "Up to date (${it.display})")
                } ?: t("暂无可用正式版本", "No stable release available")
                is UpdateCheckState.UpdateAvailable ->
                    t("发现新版本 ${checkState.info.latestVersion.display}：${checkState.info.releaseTitle}", "New version ${checkState.info.latestVersion.display}: ${checkState.info.releaseTitle}")
                is UpdateCheckState.Failed -> if (state.manualErrorVisible) {
                    t("检查失败：${checkState.message}", "Check failed: ${checkState.message}")
                } else {
                    t("自动检查暂时不可用", "Automatic check is temporarily unavailable")
                }
            },
            color = if (checkState is UpdateCheckState.Failed && state.manualErrorVisible) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheck, enabled = checkState !is UpdateCheckState.Checking) {
                Text(if (checkState is UpdateCheckState.Checking) t("检查中", "Checking") else t("检查更新", "Check for updates"))
            }
            (checkState as? UpdateCheckState.UpdateAvailable)?.let { available ->
                Button(onClick = { onOpenRelease(available.info.releaseUrl) }) { Text(t("查看 Release", "View release")) }
            }
        }
    }
}

package com.betterhv.note.sender.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentPaste
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class DashboardItemState { PENDING, TRANSFERRING, FAILED, COMPLETE }

data class DashboardItem(
    val id: String,
    val title: String,
    val detail: String,
    val state: DashboardItemState,
    val progress: Float? = null
)

data class DashboardState(
    val displayName: String = "NoteLink",
    val status: String = "正在检查设备",
    val statusDetail: String = "",
    val receiveEnabled: Boolean = true,
    val pairedDeviceName: String? = null,
    val pairingCode: String? = null,
    val queue: List<DashboardItem> = emptyList(),
    val inbox: List<DashboardItem> = emptyList(),
    val notice: String? = null
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
    val unpair: () -> Unit,
    val refreshStatus: () -> Unit,
    val dragInboxItem: @Composable (String) -> Modifier = { Modifier }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteLinkDashboard(state: DashboardState, actions: DashboardActions) {
    var showSettings by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var textValue by remember { mutableStateOf(TextFieldValue()) }
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (showSettings) "设置" else state.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        if (showSettings) {
                            IconButton(onClick = { showSettings = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                        }
                    },
                    actions = {
                        if (!showSettings) {
                            IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "设置") }
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
                title = { Text("添加文字") },
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
                    }) { Text("加入队列") }
                },
                dismissButton = { OutlinedButton(onClick = { showTextDialog = false }) { Text("取消") } }
            )
        }
    }
}

@Composable
private fun StatusBand(state: DashboardState, actions: DashboardActions) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                state.pairedDeviceName?.let { "已配对：$it" } ?: "尚未配对",
                style = MaterialTheme.typography.titleMedium
            )
            Text(state.status)
            if (state.statusDetail.isNotBlank()) {
                Text(state.statusDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.pairedDeviceName == null) {
                Text("在 N10Pro 的“设置 → 手机传输”中输入此六位配对码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.pairingCode ?: "------", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = actions.beginPairing, modifier = Modifier.weight(1f)) {
                    Text(if (state.pairedDeviceName == null) "开始配对" else "重新配对")
                }
                if (state.pairedDeviceName != null) {
                    OutlinedButton(onClick = actions.unpair, modifier = Modifier.weight(1f)) { Text("取消配对") }
                }
                IconButton(onClick = actions.refreshStatus) { Icon(Icons.Default.Refresh, "刷新连接状态") }
            }
            if (state.pairedDeviceName != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.setReceiveEnabled(!state.receiveEnabled) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (state.receiveEnabled) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                        Text(if (state.receiveEnabled) "暂停接收" else "恢复接收", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferPage(state: DashboardState, actions: DashboardActions, onAddText: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(actions.addFiles) { Icon(Icons.Default.AddPhotoAlternate, null); Text("从本地选图", Modifier.padding(start = 6.dp)) }
                OutlinedButton(onClick = onAddText) { Icon(Icons.Default.Edit, null); Text("添加文字", Modifier.padding(start = 6.dp)) }
                OutlinedButton(actions.pasteClipboard) { Icon(Icons.Default.ContentPaste, null); Text("从剪贴板粘贴", Modifier.padding(start = 6.dp)) }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TransferSection("发送队列 · ${state.queue.size} 项", Modifier.weight(1f), state.queue, "发送队列为空") { item ->
                        IconButton(onClick = { actions.deleteQueueItem(item.id) }) { Icon(Icons.Default.Delete, "删除") }
                    }
                    TransferSection("收到的导出 · ${state.inbox.size} 项", Modifier.weight(1f), state.inbox, "收件箱为空", actions.dragInboxItem) { item ->
                        InboxActions(item, actions)
                    }
                }
            } else {
                TransferSection("发送队列 · ${state.queue.size} 项", Modifier.weight(1f), state.queue, "发送队列为空") { item ->
                    IconButton(onClick = { actions.deleteQueueItem(item.id) }) { Icon(Icons.Default.Delete, "删除") }
                }
                TransferSection("收到的导出 · ${state.inbox.size} 项", Modifier.weight(1f), state.inbox, "收件箱为空", actions.dragInboxItem) { item ->
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
    IconButton(onClick = { actions.openInboxItem(item.id) }) { Icon(Icons.Default.FolderOpen, "打开") }
    IconButton(onClick = { actions.saveInboxItem(item.id) }) { Icon(Icons.Default.Save, "另存为") }
    IconButton(onClick = { actions.deleteInboxItem(item.id) }) { Icon(Icons.Default.Delete, "删除") }
}

@Composable
private fun InboxPage(items: List<DashboardItem>, actions: DashboardActions) {
    ItemList(items, "收件箱为空", actions = { item ->
        IconButton(onClick = { actions.openInboxItem(item.id) }) { Icon(Icons.Default.FolderOpen, "打开") }
        IconButton(onClick = { actions.saveInboxItem(item.id) }) { Icon(Icons.Default.Save, "另存为") }
        IconButton(onClick = { actions.deleteInboxItem(item.id) }) { Icon(Icons.Default.Delete, "删除") }
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
    var name by remember(state.displayName) { mutableStateOf(state.displayName) }
    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = { actions.saveDisplayName(name) }) { Text("保存名称") }
        }
        item { HorizontalDivider() }
        item {
            Text("N10Pro", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (state.pairedDeviceName == null) {
                Text("六位配对码仅显示在主页面", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = actions.beginPairing) { Text("重新生成配对码") }
            } else {
                Text(state.pairedDeviceName)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.beginPairing) { Text("重新配对") }
                    OutlinedButton(actions.unpair) { Text("取消配对") }
                }
            }
        }
    }
}

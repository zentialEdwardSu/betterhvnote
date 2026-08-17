package com.betterhv.note.sender

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.bluetooth.BluetoothManager
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.TransferPermissions
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.QueueItem
import com.betterhv.transfer.core.QueueState
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var queue: PhoneQueueRepository
    private lateinit var inbox: ExportInboxRepository
    private lateinit var pairing: AndroidPairingController
    private val notice = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = PhoneQueueRepository(this)
        inbox = ExportInboxRepository(this)
        pairing = AndroidPairingController(this)
        ShareShortcutPublisher.update(this, pairing.pairedDevice)
        consumeShareIntent(intent)
        setContent { BetterHvSendApp(queue, inbox, pairing, notice.value) { notice.value = it } }
        startWaiting()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeShareIntent(intent)
    }

    override fun onDestroy() { inbox.close(); queue.close(); super.onDestroy() }

    private fun consumeShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        lifecycleScope.launch {
            runCatching {
                val added = withContext(Dispatchers.IO) {
                    val destination = pairing.pairedDevice?.id
                    val uris = when (action) {
                        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                        else -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                    }
                    buildList {
                        uris.forEach { add(queue.enqueueImage(it, destination).id) }
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf(String::isNotBlank)
                            ?.let { add(queue.enqueueText(it, destination).id) }
                    }
                }
                startWaiting()
                ShareUndoNotifier.show(this@MainActivity, added)
                notice.value = "已加入发送队列"
            }.onFailure { notice.value = "加入队列失败：${it.message}" }
        }
        intent.action = null
    }

    private fun startWaiting() {
        runCatching { TransferForegroundService.sync(this, queue.items().isNotEmpty()) }
            .onFailure { notice.value = "内容已保存；授予附近设备权限后可等待发送" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BetterHvSendApp(
    queue: PhoneQueueRepository,
    inbox: ExportInboxRepository,
    pairing: AndroidPairingController,
    initialNotice: String?,
    showNotice: (String) -> Unit
) {
    val items by queue.itemFlow.collectAsState()
    val inboxItems by inbox.items.collectAsState()
    val scope = rememberCoroutineScope()
    var textDialog by remember { mutableStateOf<String?>(null) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var statusRevision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val settings = remember(context) { NoteLinkSettings(context) }
    var appDisplayName by remember { mutableStateOf(settings.displayName) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) statusRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val missingPermissions = remember(statusRevision) {
        TransferPermissions.missingPhonePermissions(context)
    }
    val connectionStatus = remember(statusRevision, items.size, pairing.pairedDevice?.id) {
        phoneConnectionStatus(
            context,
            pairing.pairedDevice != null,
            items.isNotEmpty(),
            missingPermissions.isEmpty(),
            TransferForegroundService.isReceiveEnabled(context)
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        statusRevision++
        if (TransferPermissions.hasAllPhonePermissions(context)) {
            TransferForegroundService.sync(context, items.isNotEmpty())
            showNotice("附近设备权限已授予")
        } else showNotice("没有附近设备权限时仍可排队，但 Note 无法连接")
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { uris.forEach { queue.enqueueImage(it, pairing.pairedDevice?.id) } } }
                .onSuccess { TransferForegroundService.sync(queueContext(queue), queue.items().isNotEmpty()) }
                .onFailure { showNotice("图片加入失败：${it.message}") }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { queue.enqueueImage(uri, pairing.pairedDevice?.id) } }
                .onSuccess { TransferForegroundService.sync(queueContext(queue), true) }
                .onFailure { showNotice("照片加入失败：${it.message}") }
            cameraFile?.delete()
            cameraFile = null
            cameraUri = null
        } else {
            cameraFile?.delete()
            cameraFile = null
            cameraUri = null
        }
    }

    MaterialTheme {
        if (showSettings) {
            SettingsScreen(
                displayName = appDisplayName,
                sentCacheCount = items.count {
                    it.state == QueueState.PENDING || it.state == QueueState.FAILED
                },
                receivedCacheCount = inboxItems.count { it.state != InboxExportState.RECEIVING },
                message = settingsMessage,
                onBack = { showSettings = false; settingsMessage = null },
                onSaveName = { requestedName ->
                    runCatching {
                        settings.displayName = requestedName
                        appDisplayName = settings.displayName
                        TransferForegroundService.settingsChanged(context, items.isNotEmpty())
                    }.onSuccess {
                        settingsMessage = "显示名称已保存"
                    }.onFailure {
                        settingsMessage = it.message ?: "保存失败"
                    }
                },
                onClearSent = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { queue.clearCache() }
                        }.onSuccess { removed ->
                            TransferForegroundService.sync(context, queue.items().isNotEmpty())
                            settingsMessage = if (removed == 0) "没有可清理的发送缓存"
                            else "已清理 $removed 项发送缓存"
                        }.onFailure {
                            settingsMessage = "发送缓存清理失败：${it.message}"
                        }
                    }
                },
                onClearReceived = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { inbox.clearCache() }
                        }.onSuccess { removed ->
                            settingsMessage = if (removed == 0) "没有可清理的接收缓存"
                            else "已清理 $removed 项接收缓存"
                        }.onFailure {
                            settingsMessage = "接收缓存清理失败：${it.message}"
                        }
                    }
                }
            )
        } else Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(appDisplayName) },
                    actions = {
                        IconButton(onClick = { settingsMessage = null; showSettings = true }) {
                            Icon(Icons.Default.Settings, "设置")
                        }
                    }
                )
            },
            floatingActionButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FloatingActionButton(onClick = {
                        val context = queueContext(queue)
                        val file = File(context.filesDir, "camera/capture-${System.currentTimeMillis()}.jpg")
                            .also { it.parentFile?.mkdirs() }
                        cameraFile = file
                        cameraUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        camera.launch(cameraUri!!)
                    }) { Icon(Icons.Default.CameraAlt, "拍照") }
                    FloatingActionButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                        Icon(Icons.Default.AddPhotoAlternate, "选择图片")
                    }
                    FloatingActionButton(onClick = {
                        val context = queueContext(queue)
                        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                        val value = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (value.isBlank()) showNotice("剪贴板没有文字") else textDialog = value
                    }) { Icon(Icons.Default.ContentPaste, "从剪贴板添加") }
                }
            }
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    DeviceCard(
                        deviceName = pairing.pairedDevice?.name,
                        connectionStatus = connectionStatus,
                        showPermissionButton = missingPermissions.isNotEmpty(),
                        onPair = {
                            val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
                            pairing.confirmManual("betterhv-note", "N10Pro", code)
                            queue.reassignPending("betterhv-note")
                            ShareShortcutPublisher.update(queueContext(queue), pairing.pairedDevice)
                            TransferForegroundService.setReceiveEnabled(context, true)
                            TransferForegroundService.sync(queueContext(queue), queue.items().isNotEmpty())
                            statusRevision++
                            pairingCode = code
                        },
                        onUnpair = {
                            pairing.unpair(); ShareShortcutPublisher.update(queueContext(queue), null)
                            TransferForegroundService.sync(context, queue.items().isNotEmpty())
                            statusRevision++
                            showNotice("已取消配对；队列内容保留")
                        },
                        onPermissions = {
                            permissionLauncher.launch(TransferPermissions.missingPhonePermissions(context))
                        },
                        onRefresh = {
                            statusRevision++
                            if (TransferPermissions.hasAllPhonePermissions(context)) {
                                runCatching { TransferForegroundService.sync(context, queue.items().isNotEmpty()) }
                                    .onSuccess { showNotice("连接状态已刷新") }
                                    .onFailure { showNotice("刷新失败：${it.message}") }
                            }
                        },
                        receiveEnabled = TransferForegroundService.isReceiveEnabled(context),
                        onReceiveToggle = {
                            val enabled = !TransferForegroundService.isReceiveEnabled(context)
                            TransferForegroundService.setReceiveEnabled(context, enabled)
                            TransferForegroundService.sync(context, queue.items().isNotEmpty())
                            statusRevision++
                        }
                    )
                }
                initialNotice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
                item {
                    Text("发送队列 · ${items.size} 项", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                }
                if (items.isEmpty()) item {
                    Text("使用下方按钮拍照、选图或读取剪贴板；也可以从其他应用的分享菜单发送到这里。")
                }
                items(items, key = { it.id }) { item ->
                    QueueCard(item,
                        onUp = { queue.move(item.id, -1) },
                        onDown = { queue.move(item.id, 1) },
                        onDelete = {
                            queue.delete(item.id)
                            TransferForegroundService.sync(queueContext(queue), queue.items().isNotEmpty())
                        })
                }
                item {
                    Text(
                        "收到的导出 · ${inboxItems.count { it.state == InboxExportState.COMPLETE }} 项",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (inboxItems.isEmpty()) item { Text("Note 发送的 PDF 与 PNG 会保存在这里。") }
                items(inboxItems, key = { it.artifactId }) { item ->
                    InboxCard(
                        item = item,
                        onOpen = {
                            val uri = inbox.contentUri(item)
                            runCatching {
                                context.startActivity(viewExportIntent(context, item, uri))
                            }.onFailure { showNotice("无法打开文件：${it.message ?: "没有兼容的应用"}") }
                        },
                        onShare = {
                            val uri = inbox.contentUri(item)
                            runCatching {
                                context.startActivity(shareExportIntent(context, item, uri))
                            }.onFailure { showNotice("无法分享文件：${it.message ?: "没有兼容的应用"}") }
                        },
                        onSave = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { inbox.saveToDownloads(item) } }
                                    .onSuccess { showNotice("已保存到 Downloads/NoteLink") }
                                    .onFailure { showNotice("保存失败：${it.message}") }
                            }
                        },
                        onDelete = { inbox.delete(item.artifactId) }
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }

        if (!showSettings) textDialog?.let { initial ->
            var value by remember(initial) { mutableStateOf(initial) }
            AlertDialog(onDismissRequest = { textDialog = null }, title = { Text("加入文字") },
                text = { OutlinedTextField(value, { value = it }, minLines = 3, maxLines = 8) },
                confirmButton = { Button(onClick = {
                    runCatching { queue.enqueueText(value, pairing.pairedDevice?.id) }
                        .onSuccess { TransferForegroundService.sync(queueContext(queue), true); textDialog = null }
                        .onFailure { showNotice(it.message ?: "文字加入失败") }
                }) { Text("加入队列") } },
                dismissButton = { OutlinedButton(onClick = { textDialog = null }) { Text("取消") } })
        }
        if (!showSettings) pairingCode?.let { code ->
            AlertDialog(onDismissRequest = { pairingCode = null }, title = { Text("在 Note 上完成配对") },
                text = { Column { Text("在 N10Pro 的“设置 → 手机传输”中输入："); Text(code,
                    style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold) } },
                confirmButton = { Button(onClick = { pairingCode = null }) { Text("完成") } })
        }
    }
}

private enum class CacheToClear { SENT, RECEIVED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    displayName: String,
    sentCacheCount: Int,
    receivedCacheCount: Int,
    message: String?,
    onBack: () -> Unit,
    onSaveName: (String) -> Unit,
    onClearSent: () -> Unit,
    onClearReceived: () -> Unit
) {
    var editedName by remember(displayName) { mutableStateOf(displayName) }
    var confirmation by remember { mutableStateOf<CacheToClear?>(null) }
    val nameError = runCatching { NoteLinkSettings.validateDisplayName(editedName) }
        .exceptionOrNull()?.message

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("显示名称", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("名称") },
                        isError = nameError != null,
                        supportingText = nameError?.let { error -> { Text(error) } }
                    )
                    Button(
                        onClick = { onSaveName(editedName) },
                        enabled = nameError == null,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Save, null)
                        Text("保存")
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
            item { HorizontalDivider() }
            item {
                Text(
                    "缓存",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("发送缓存") },
                    supportingContent = { Text("$sentCacheCount 项可清理") },
                    trailingContent = {
                        IconButton(
                            onClick = { confirmation = CacheToClear.SENT },
                            enabled = sentCacheCount > 0
                        ) { Icon(Icons.Default.DeleteSweep, "清理发送缓存") }
                    }
                )
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 20.dp)) }
            item {
                ListItem(
                    headlineContent = { Text("接收缓存") },
                    supportingContent = { Text("$receivedCacheCount 项可清理") },
                    trailingContent = {
                        IconButton(
                            onClick = { confirmation = CacheToClear.RECEIVED },
                            enabled = receivedCacheCount > 0
                        ) { Icon(Icons.Default.DeleteSweep, "清理接收缓存") }
                    }
                )
            }
        }
    }

    confirmation?.let { target ->
        val isSent = target == CacheToClear.SENT
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (isSent) "清理发送缓存？" else "清理接收缓存？") },
            text = {
                Text(
                    if (isSent) "将删除待发送和发送失败的项目，正在传输的项目会保留。"
                    else "将删除已接收和接收失败的文件，正在接收的项目会保留。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmation = null
                    if (isSent) onClearSent() else onClearReceived()
                }) { Text("清理") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmation = null }) { Text("取消") }
            }
        )
    }
}

internal fun viewExportIntent(context: Context, item: InboxExport, uri: Uri): Intent {
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, item.mimeType)
        clipData = ClipData.newUri(context.contentResolver, item.displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun shareExportIntent(context: Context, item: InboxExport, uri: Uri): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, item.displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "分享导出").apply {
        clipData = send.clipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@Composable private fun DeviceCard(
    deviceName: String?,
    connectionStatus: String,
    showPermissionButton: Boolean,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
    onPermissions: () -> Unit,
    onRefresh: () -> Unit,
    receiveEnabled: Boolean,
    onReceiveToggle: () -> Unit
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(deviceName?.let { "已配对：$it" } ?: "尚未配对", style = MaterialTheme.typography.titleMedium)
            Text(connectionStatus)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onPair, modifier = Modifier.weight(1f)) {
                    Text(if (deviceName == null) "开始配对" else "重新配对")
                }
                if (deviceName != null) OutlinedButton(onClick = onUnpair, modifier = Modifier.weight(1f)) {
                    Text("取消配对")
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "刷新连接状态") }
            }
            if (showPermissionButton || deviceName != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showPermissionButton) OutlinedButton(
                        onClick = onPermissions,
                        modifier = Modifier.weight(1f)
                    ) { Text("授予权限") }
                    if (deviceName != null) OutlinedButton(
                        onClick = onReceiveToggle,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (receiveEnabled) "暂停接收" else "恢复接收")
                    }
                }
            }
        }
    }
}

@Composable private fun QueueCard(item: QueueItem, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (item.kind == ContentKind.IMAGE) "图片" else "文字", fontWeight = FontWeight.Bold)
                Text(item.displayName.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${item.state} · ${formatSize(item.byteLength)}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onUp) { Icon(Icons.Default.KeyboardArrowUp, "上移") }
            IconButton(onClick = onDown) { Icon(Icons.Default.KeyboardArrowDown, "下移") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun InboxCard(
    item: InboxExport,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(item.displayName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when (item.state) {
                    InboxExportState.RECEIVING -> "接收中 · ${formatSize(item.byteLength)}"
                    InboxExportState.COMPLETE -> "已接收 · ${formatSize(item.byteLength)}"
                    InboxExportState.FAILED -> "接收失败 · ${item.error.orEmpty()}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.state == InboxExportState.COMPLETE) {
                    IconButton(onClick = onOpen) { Icon(Icons.Default.OpenInNew, "打开") }
                    IconButton(onClick = onShare) { Icon(Icons.Default.Share, "分享") }
                    IconButton(onClick = onSave) { Icon(Icons.Default.Download, "保存到 Downloads") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
            }
        }
    }
}

private fun phoneConnectionStatus(
    context: Context,
    isPaired: Boolean,
    hasItems: Boolean,
    permissionsGranted: Boolean,
    receiveEnabled: Boolean
): String {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return "此手机不支持 BLE"
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return "此手机不支持 Wi-Fi Direct"
    if (!permissionsGranted) return "需要附近设备权限"
    if (context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) return "蓝牙已关闭"
    if (context.getSystemService(WifiManager::class.java)?.isWifiEnabled != true) return "WLAN 已关闭"
    if (!isPaired) return "生成配对码后在 Note 上输入"
    if (!receiveEnabled) return "已暂停接收来自 Note 的导出"
    return if (hasItems) "已就绪，正在等待 Note 获取" else "已就绪，暂无待发送内容"
}

/** Repository intentionally owns application context; this avoids leaking the Activity to launchers. */
private fun queueContext(queue: PhoneQueueRepository): Context {
    return queue.appContext
}

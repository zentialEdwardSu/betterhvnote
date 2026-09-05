package com.betterhv.note.sender

import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.betterhv.note.sender.shared.NoteLinkLanguage
import com.betterhv.note.sender.shared.displayName
import com.betterhv.note.sender.shared.noteLinkText
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.TransferPermissions
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.PairedDevice
import com.betterhv.transfer.core.QueueItem
import com.betterhv.transfer.core.QueueState
import com.betterhv.transfer.core.TransferLogEntry
import com.betterhv.transfer.core.TransferLogLevel
import com.betterhv.transfer.core.TransferModes
import com.betterhv.transfer.core.TransferSnapshot
import com.betterhv.transfer.core.isActiveTransferPhase
import com.betterhv.update.UpdateCheckState
import com.betterhv.update.UpdateChecker
import com.betterhv.update.UpdateInfo
import com.betterhv.update.UpdateProduct
import com.betterhv.update.UpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.UUID

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

    override fun onDestroy() {
        inbox.close()
        queue.close()
        super.onDestroy()
    }

    private fun consumeShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        lifecycleScope.launch {
            runCatching {
                val added = withContext(Dispatchers.IO) {
                    val destination = pairing.pairedDevice?.id
                    val uris = when (action) {
                        Intent.ACTION_SEND -> listOfNotNull(
                            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        )
                        else -> IntentCompat.getParcelableArrayListExtra(
                            intent,
                            Intent.EXTRA_STREAM,
                            Uri::class.java
                        ).orEmpty()
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
@Suppress("MultiLineIfElse")
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
    val transfer by NoteLinkTransferRuntime.snapshot.collectAsState()
    val transferLog by NoteLinkTransferRuntime.eventHistory.collectAsState()
    val scope = rememberCoroutineScope()
    var textDialog by remember { mutableStateOf<String?>(null) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var statusRevision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val settings = remember(context) { NoteLinkSettings(context) }
    var appDisplayName by remember { mutableStateOf(settings.displayName) }
    var showRecentTransferEvents by remember { mutableStateOf(settings.showRecentTransferEvents) }
    var language by remember { mutableStateOf(settings.language) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    val pairedDevices = remember(statusRevision) { pairing.pairedClients }
    val selectedDevice = pairedDevices.maxByOrNull(PairedDevice::lastUsedAt)
    val updateChecker = remember { UpdateChecker() }
    var updateUiState by remember { mutableStateOf(UpdateUiState()) }
    val openRelease: (String) -> Unit = { url ->
        if (!UpdateChecker.isTrustedReleaseUrl(url)) {
            showNotice(noteLinkText("Release 地址无效", "Invalid release URL"))
        } else {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                .onFailure {
                    showNotice(
                        noteLinkText("无法打开 Release 页面：${it.message}", "Cannot open release page: ${it.message}")
                    )
                }
        }
    }
    val checkForUpdates: (Boolean) -> Unit = { manual ->
        if (updateUiState.checkState !is UpdateCheckState.Checking) {
            updateUiState = updateUiState.checking(manual)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    updateChecker.check(UpdateProduct.NOTELINK, BuildConfig.VERSION_NAME)
                }
                updateUiState = updateUiState.completed(result, manual)
            }
        }
    }
    LaunchedEffect(updateChecker) {
        updateUiState = updateUiState.checking(manual = false)
        val result = withContext(Dispatchers.IO) {
            updateChecker.check(UpdateProduct.NOTELINK, BuildConfig.VERSION_NAME)
        }
        updateUiState = updateUiState.completed(result, manual = false)
    }
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
    val connectionStatus = remember(statusRevision, items.size, selectedDevice?.id, language) {
        phoneConnectionStatus(
            context,
            selectedDevice != null,
            items.isNotEmpty(),
            missingPermissions.isEmpty(),
            TransferForegroundService.isReceiveEnabled(context)
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        statusRevision++
        if (TransferPermissions.hasAllPhonePermissions(context)) {
            TransferForegroundService.sync(context, items.isNotEmpty())
            showNotice(noteLinkText("附近设备权限已授予", "Nearby devices permission granted"))
        } else {
            showNotice(
                noteLinkText(
                    "没有附近设备权限时仍可排队，但 Note 无法连接",
                    "Items can still be queued, but Note cannot connect without Nearby devices permission"
                )
            )
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { uris.forEach { queue.enqueueImage(it, selectedDevice?.id) } } }
                .onSuccess { TransferForegroundService.sync(queueContext(queue), queue.items().isNotEmpty()) }
                .onFailure { showNotice(noteLinkText("图片加入失败：${it.message}", "Could not add image: ${it.message}")) }
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    uris.forEach { queue.enqueuePdf(it, selectedDevice?.id) }
                }
            }
                .onSuccess { TransferForegroundService.sync(queueContext(queue), queue.items().isNotEmpty()) }
                .onFailure { showNotice(noteLinkText("PDF 加入失败：${it.message}", "Could not add PDF: ${it.message}")) }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { queue.enqueueImage(uri, selectedDevice?.id) } }
                    .onSuccess { TransferForegroundService.sync(queueContext(queue), true) }
                    .onFailure {
                        showNotice(
                            noteLinkText("照片加入失败：${it.message}", "Could not add photo: ${it.message}")
                        )
                    }
                cameraFile?.delete()
                cameraFile = null
                cameraUri = null
            }
        } else {
            cameraFile?.delete()
            cameraFile = null
            cameraUri = null
        }
    }

    MaterialTheme(colorScheme = NoteLinkColorScheme) {
        if (showSettings) {
            SettingsScreen(
                displayName = appDisplayName,
                sentCacheCount = items.count {
                    it.state == QueueState.PENDING || it.state == QueueState.FAILED
                },
                receivedCacheCount = inboxItems.count { it.state != InboxExportState.RECEIVING },
                transfer = transfer,
                transferLog = transferLog,
                endpointName = selectedDevice?.name,
                showRecentTransferEvents = showRecentTransferEvents,
                language = language,
                message = settingsMessage,
                updateUiState = updateUiState,
                onBack = {
                    showSettings = false
                    settingsMessage = null
                },
                onSaveName = { requestedName ->
                    runCatching {
                        settings.displayName = requestedName
                        appDisplayName = settings.displayName
                        TransferForegroundService.settingsChanged(context, items.isNotEmpty())
                    }.onSuccess {
                        settingsMessage = noteLinkText("显示名称已保存", "Display name saved")
                    }.onFailure {
                        settingsMessage = it.message ?: noteLinkText("保存失败", "Save failed")
                    }
                },
                onShowRecentTransferEventsChange = {
                    settings.showRecentTransferEvents = it
                    showRecentTransferEvents = it
                },
                onLanguageChange = {
                    settings.language = it
                    language = it
                },
                onClearSent = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { queue.clearCache() }
                        }.onSuccess { removed ->
                            TransferForegroundService.sync(context, queue.items().isNotEmpty())
                            settingsMessage = if (removed == 0) {
                                noteLinkText("没有可清理的发送缓存", "No send cache to clear")
                            } else {
                                noteLinkText("已清理 $removed 项发送缓存", "Cleared $removed send-cache items")
                            }
                        }.onFailure {
                            settingsMessage = noteLinkText("发送缓存清理失败：${it.message}", "Could not clear send cache: ${it.message}")
                        }
                    }
                },
                onClearReceived = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { inbox.clearCache() }
                        }.onSuccess { removed ->
                            settingsMessage = if (removed == 0) {
                                noteLinkText("没有可清理的接收缓存", "No receive cache to clear")
                            } else {
                                noteLinkText("已清理 $removed 项接收缓存", "Cleared $removed receive-cache items")
                            }
                        }.onFailure {
                            settingsMessage = noteLinkText("接收缓存清理失败：${it.message}", "Could not clear receive cache: ${it.message}")
                        }
                    }
                },
                onCheckUpdate = { checkForUpdates(true) },
                onOpenRelease = openRelease,
            )
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(appDisplayName) },
                        actions = {
                            IconButton(onClick = {
                                settingsMessage = null
                                showSettings = true
                            }) {
                                Icon(Icons.Default.Settings, noteLinkText("设置", "Settings"))
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
                        }) { Icon(Icons.Default.CameraAlt, noteLinkText("拍照", "Take photo")) }
                        FloatingActionButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                            Icon(Icons.Default.AddPhotoAlternate, noteLinkText("选择图片", "Choose images"))
                        }
                        FloatingActionButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Default.PictureAsPdf, noteLinkText("选择 PDF", "Choose PDF"))
                        }
                        FloatingActionButton(onClick = {
                            val context = queueContext(queue)
                            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                            val value = clip?.takeIf { it.itemCount > 0 }?.getItemAt(
                                0
                            )?.coerceToText(context)?.toString().orEmpty()
                            if (value.isBlank()) {
                                showNotice(
                                    noteLinkText("剪贴板没有文字", "Clipboard contains no text")
                                )
                            } else {
                                textDialog = value
                            }
                        }) { Icon(Icons.Default.ContentPaste, noteLinkText("从剪贴板添加", "Add from clipboard")) }
                    }
                }
            ) { padding ->
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    updateUiState.visibleUpdate?.let { info ->
                        item {
                            UpdateNoticeCard(
                                info = info,
                                onOpenRelease = { openRelease(info.releaseUrl) },
                                onDismiss = { updateUiState = updateUiState.dismissBanner() },
                            )
                        }
                    }
                    item {
                        DeviceCard(
                            devices = pairedDevices,
                            selectedDeviceId = selectedDevice?.id,
                            connectionStatus = connectionStatus,
                            showPermissionButton = missingPermissions.isNotEmpty(),
                            onPair = {
                                val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
                                val firstPairing = pairedDevices.isEmpty()
                                val added = pairing.confirmManual("pending:${UUID.randomUUID()}", "N10Pro", code)
                                if (firstPairing) queue.reassignPending(added.id)
                                ShareShortcutPublisher.update(queueContext(queue), added)
                                TransferForegroundService.setReceiveEnabled(context, true)
                                TransferForegroundService.sync(queueContext(queue), queue.items().isNotEmpty())
                                statusRevision++
                                pairingCode = code
                            },
                            onSelect = { id ->
                                pairing.markLastUsed(id)
                                ShareShortcutPublisher.update(queueContext(queue), pairing.pairedClient(id))
                                statusRevision++
                            },
                            onUnpair = { id ->
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { pairing.unpair(id) } }
                                        .onSuccess {
                                            ShareShortcutPublisher.update(queueContext(queue), pairing.pairedDevice)
                                            TransferForegroundService.sync(context, queue.items().isNotEmpty())
                                            statusRevision++
                                            showNotice(
                                                noteLinkText("已取消配对；队列内容保留", "Unpaired; queue contents were kept")
                                            )
                                        }
                                        .onFailure {
                                            showNotice(
                                                noteLinkText("取消配对失败：${it.message}", "Could not unpair: ${it.message}")
                                            )
                                        }
                                }
                            },
                            onPermissions = {
                                permissionLauncher.launch(TransferPermissions.missingPhonePermissions(context))
                            },
                            onRefresh = {
                                statusRevision++
                                if (TransferPermissions.hasAllPhonePermissions(context)) {
                                    runCatching { TransferForegroundService.sync(context, queue.items().isNotEmpty()) }
                                        .onSuccess {
                                            showNotice(
                                                noteLinkText("连接状态已刷新", "Connection status refreshed")
                                            )
                                        }
                                        .onFailure {
                                            showNotice(
                                                noteLinkText("刷新失败：${it.message}", "Refresh failed: ${it.message}")
                                            )
                                        }
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
                    if (transfer.phase.isActiveTransferPhase || showRecentTransferEvents && transferLog.isNotEmpty()) {
                        item {
                            PhoneTransferStatus(
                                transfer,
                                transferLog,
                                selectedDevice?.name,
                                showRecentTransferEvents,
                                NoteLinkTransferRuntime::cancel
                            )
                        }
                    }
                    initialNotice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
                    item {
                        Text(
                            noteLinkText("发送队列 · ${items.size} 项", "Send queue · ${items.size}"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (items.isEmpty()) {
                        item {
                            Text(
                                noteLinkText(
                                    "使用下方按钮拍照、选图或读取剪贴板；也可以从其他应用的分享菜单发送到这里。",
                                    "Use the buttons below to take a photo, choose files, or read the clipboard. You can also share here from other apps."
                                )
                            )
                        }
                    }
                    items(items, key = { it.id }) { item ->
                        QueueCard(
                            item,
                            onUp = { queue.move(item.id, -1) },
                            onDown = { queue.move(item.id, 1) },
                            onDelete = {
                                queue.delete(item.id)
                                TransferForegroundService.sync(queueContext(queue), queue.items().isNotEmpty())
                            }
                        )
                    }
                    item {
                        Text(
                            noteLinkText(
                                "收到的导出 · ${inboxItems.count { it.state == InboxExportState.COMPLETE }} 项",
                                "Received exports · ${inboxItems.count { it.state == InboxExportState.COMPLETE }}"
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (inboxItems.isEmpty()) {
                        item {
                            Text(
                                noteLinkText("Note 发送的 PDF 与 PNG 会保存在这里。", "PDF and PNG exports sent by Note appear here.")
                            )
                        }
                    }
                    items(inboxItems, key = { it.artifactId }) { item ->
                        InboxCard(
                            item = item,
                            onOpen = {
                                val uri = inbox.contentUri(item)
                                runCatching {
                                    context.startActivity(viewExportIntent(context, item, uri))
                                }.onFailure {
                                    showNotice(
                                        noteLinkText(
                                            "无法打开文件：${it.message ?: "没有兼容的应用"}",
                                            "Cannot open file: ${it.message ?: "no compatible app"}"
                                        )
                                    )
                                }
                            },
                            onShare = {
                                val uri = inbox.contentUri(item)
                                runCatching {
                                    context.startActivity(shareExportIntent(context, item, uri))
                                }.onFailure {
                                    showNotice(
                                        noteLinkText(
                                            "无法分享文件：${it.message ?: "没有兼容的应用"}",
                                            "Cannot share file: ${it.message ?: "no compatible app"}"
                                        )
                                    )
                                }
                            },
                            onSave = {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { inbox.saveToDownloads(item) } }
                                        .onSuccess {
                                            showNotice(
                                                noteLinkText("已保存到 Downloads/NoteLink", "Saved to Downloads/NoteLink")
                                            )
                                        }
                                        .onFailure {
                                            showNotice(
                                                noteLinkText("保存失败：${it.message}", "Save failed: ${it.message}")
                                            )
                                        }
                                }
                            },
                            onDelete = { inbox.delete(item.artifactId) }
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }

        if (!showSettings) {
            textDialog?.let { initial ->
                var value by remember(initial) { mutableStateOf(initial) }
                AlertDialog(
                    onDismissRequest = { textDialog = null },
                    title = { Text(noteLinkText("加入文字", "Add text")) },
                    text = { OutlinedTextField(value, { value = it }, minLines = 3, maxLines = 8) },
                    confirmButton = {
                        Button(onClick = {
                            runCatching { queue.enqueueText(value, selectedDevice?.id) }
                                .onSuccess {
                                    TransferForegroundService.sync(queueContext(queue), true)
                                    textDialog = null
                                }
                                .onFailure { showNotice(it.message ?: noteLinkText("文字加入失败", "Could not add text")) }
                        }) { Text(noteLinkText("加入队列", "Add to queue")) }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { textDialog = null }
                        ) { Text(noteLinkText("取消", "Cancel")) }
                    },
                )
            }
        }
        if (!showSettings) {
            pairingCode?.let { code ->
                AlertDialog(
                    onDismissRequest = { pairingCode = null },
                    title = { Text(noteLinkText("在 Note 上完成配对", "Complete pairing on Note")) },
                    text = {
                        Column {
                            Text(
                                noteLinkText("在 N10Pro 的“设置 → 手机传输”中输入：", "Enter in Settings > NoteLink on the N10Pro:")
                            )
                            Text(
                                code,
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    confirmButton = { Button(onClick = { pairingCode = null }) { Text(noteLinkText("完成", "Done")) } }
                )
            }
        }
    }
}

@Composable
private fun PhoneTransferStatus(
    snapshot: TransferSnapshot,
    log: List<TransferLogEntry>,
    endpointName: String?,
    showRecentEvents: Boolean,
    onCancel: () -> Unit
) {
    if (!snapshot.phase.isActiveTransferPhase && (!showRecentEvents || log.isEmpty())) return
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(noteLinkText("传输状态", "Transfer status"), style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(
                    "BLE",
                    snapshot.phase.name.replace('_', ' '),
                    snapshot.mode?.name,
                    "SSID ${snapshot.ssidMatch.name}"
                )
                    .joinToString(" · ")
            )
            Text(
                "${noteLinkText("本端", "Local")} ${TransferModes.describe(snapshot.localModes)} · " +
                    "${noteLinkText("对端", "Remote")} ${TransferModes.describe(snapshot.remoteModes)}" +
                    " · ${noteLinkText("尝试", "attempt")} ${snapshot.attempt} · fallback ${snapshot.fallbackCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            snapshot.endpoint?.let {
                Text(
                    "${noteLinkText(
                        "端点",
                        "Endpoint"
                    )} ${endpointName ?: snapshot.deviceId ?: noteLinkText("未知设备", "Unknown device")} · ${it.host}:${it.port}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Wi-Fi Direct ${noteLinkText("组", "group")} ${if (snapshot.wifiDirectGroupReady) noteLinkText("已就绪", "ready") else noteLinkText("未就绪", "not ready")}" +
                    (snapshot.operationId?.let { " · ${noteLinkText("操作", "operation")} ${it.toString().take(8)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall
            )
            if (snapshot.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (snapshot.bytesTransferred.toFloat() / snapshot.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${phoneFormatBytes(snapshot.bytesTransferred)} / ${phoneFormatBytes(snapshot.totalBytes)}" +
                        " · ${noteLinkText("当前", "current")} ${phoneFormatBytes(snapshot.bytesPerSecond)}/s" +
                        " · ${noteLinkText("平均", "average")} ${phoneFormatBytes(snapshot.averageBytesPerSecond)}/s" +
                        (snapshot.etaMillis?.let { " · ETA ${phoneFormatDuration(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            snapshot.fallbackReason?.let {
                Text("fallback ${it.code}: ${it.message}", color = MaterialTheme.colorScheme.tertiary)
            }
            snapshot.lastFailure?.let {
                Text(
                    "${it.code}: ${it.message} · ${if (it.recoverable) {
                        noteLinkText(
                            "可重试",
                            "retryable"
                        )
                    } else {
                        noteLinkText("不可重试", "not retryable")
                    }}",
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (snapshot.canCancel) {
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, null)
                    Text(noteLinkText("取消传输", "Cancel transfer"), Modifier.padding(start = 6.dp))
                }
            }
            if (showRecentEvents && log.isNotEmpty()) {
                Text(noteLinkText("最近事件", "Recent events"), style = MaterialTheme.typography.labelLarge)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState())
                ) {
                    log.asReversed().forEach { entry ->
                        Text(
                            "${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(entry.timestampMillis))} " +
                                "${entry.category} ${entry.detail}",
                            modifier = Modifier.fillMaxWidth().height(20.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.level == TransferLogLevel.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun phoneFormatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GiB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MiB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun phoneFormatDuration(millis: Long): String {
    val seconds = (millis / 1_000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

private enum class CacheToClear { SENT, RECEIVED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    displayName: String,
    sentCacheCount: Int,
    receivedCacheCount: Int,
    transfer: TransferSnapshot,
    transferLog: List<TransferLogEntry>,
    endpointName: String?,
    showRecentTransferEvents: Boolean,
    language: NoteLinkLanguage,
    message: String?,
    updateUiState: UpdateUiState,
    onBack: () -> Unit,
    onSaveName: (String) -> Unit,
    onShowRecentTransferEventsChange: (Boolean) -> Unit,
    onLanguageChange: (NoteLinkLanguage) -> Unit,
    onClearSent: () -> Unit,
    onClearReceived: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    var editedName by remember(displayName) { mutableStateOf(displayName) }
    var confirmation by remember { mutableStateOf<CacheToClear?>(null) }
    val nameError = runCatching { NoteLinkSettings.validateDisplayName(editedName) }
        .exceptionOrNull()?.message

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(noteLinkText("设置", "Settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, noteLinkText("返回", "Back"))
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
                PhoneTransferStatus(
                    transfer,
                    transferLog,
                    endpointName,
                    showRecentTransferEvents,
                    NoteLinkTransferRuntime::cancel
                )
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 20.dp)) }
            item {
                ListItem(
                    headlineContent = { Text(noteLinkText("显示最近传输事件", "Show recent transfer events")) },
                    trailingContent = {
                        Switch(
                            checked = showRecentTransferEvents,
                            onCheckedChange = onShowRecentTransferEventsChange
                        )
                    }
                )
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 20.dp)) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(noteLinkText("语言", "Language"), style = MaterialTheme.typography.titleMedium)
                    NoteLinkLanguage.entries.forEach { option ->
                        OutlinedButton(
                            onClick = { onLanguageChange(option) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Text((if (option == language) "✓ " else "") + option.displayName())
                        }
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 20.dp)) }
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        noteLinkText("显示名称", "Display name"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(noteLinkText("名称", "Name")) },
                        isError = nameError != null,
                        supportingText = nameError?.let { error -> { Text(error) } }
                    )
                    Button(
                        onClick = { onSaveName(editedName) },
                        enabled = nameError == null,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Save, null)
                        Text(noteLinkText("保存", "Save"))
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
            item { HorizontalDivider() }
            item {
                UpdateSettingsCard(
                    currentVersion = BuildConfig.VERSION_NAME,
                    state = updateUiState,
                    onCheck = onCheckUpdate,
                    onOpenRelease = onOpenRelease,
                )
            }
            item { HorizontalDivider() }
            item {
                Text(
                    noteLinkText("缓存", "Cache"),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(noteLinkText("发送缓存", "Send cache")) },
                    supportingContent = {
                        Text(
                            noteLinkText("$sentCacheCount 项可清理", "$sentCacheCount items can be removed")
                        )
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { confirmation = CacheToClear.SENT },
                            enabled = sentCacheCount > 0
                        ) { Icon(Icons.Default.DeleteSweep, noteLinkText("清理发送缓存", "Clear send cache")) }
                    }
                )
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 20.dp)) }
            item {
                ListItem(
                    headlineContent = { Text(noteLinkText("接收缓存", "Receive cache")) },
                    supportingContent = {
                        Text(
                            noteLinkText("$receivedCacheCount 项可清理", "$receivedCacheCount items can be removed")
                        )
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { confirmation = CacheToClear.RECEIVED },
                            enabled = receivedCacheCount > 0
                        ) { Icon(Icons.Default.DeleteSweep, noteLinkText("清理接收缓存", "Clear receive cache")) }
                    }
                )
            }
        }
    }

    confirmation?.let { target ->
        val isSent = target == CacheToClear.SENT
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = {
                Text(
                    if (isSent) {
                        noteLinkText(
                            "清理发送缓存？",
                            "Clear send cache?"
                        )
                    } else {
                        noteLinkText("清理接收缓存？", "Clear receive cache?")
                    }
                )
            },
            text = {
                Text(
                    if (isSent) {
                        noteLinkText(
                            "将删除待发送和发送失败的项目，正在传输的项目会保留。",
                            "Pending and failed items will be deleted. Active transfers will be kept."
                        )
                    } else {
                        noteLinkText(
                            "将删除已接收和接收失败的文件，正在接收的项目会保留。",
                            "Received and failed files will be deleted. Active transfers will be kept."
                        )
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmation = null
                    if (isSent) onClearSent() else onClearReceived()
                }) { Text(noteLinkText("清理", "Clear")) }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmation = null }) { Text(noteLinkText("取消", "Cancel")) }
            }
        )
    }
}

@Composable
private fun UpdateNoticeCard(
    info: UpdateInfo,
    onOpenRelease: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                noteLinkText("发现新版本 ${info.latestVersion.display}", "New version ${info.latestVersion.display}"),
                style = MaterialTheme.typography.titleMedium
            )
            Text(info.releaseTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenRelease) { Text(noteLinkText("查看 Release", "View release")) }
                OutlinedButton(onClick = onDismiss) { Text(noteLinkText("关闭", "Close")) }
            }
        }
    }
}

@Composable
private fun UpdateSettingsCard(
    currentVersion: String,
    state: UpdateUiState,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    val checkState = state.checkState
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            noteLinkText("应用更新", "App updates"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(noteLinkText("当前版本 $currentVersion", "Current version $currentVersion"))
        Text(
            when (checkState) {
                UpdateCheckState.Idle -> noteLinkText("尚未检查", "Not checked yet")
                UpdateCheckState.Checking -> noteLinkText("正在检查 GitHub Releases…", "Checking GitHub Releases...")
                is UpdateCheckState.UpToDate -> checkState.latestVersion?.let {
                    noteLinkText("已是最新版本（${it.display}）", "Up to date (${it.display})")
                } ?: noteLinkText("暂无可用正式版本", "No stable release available")
                is UpdateCheckState.UpdateAvailable ->
                    noteLinkText(
                        "发现新版本 ${checkState.info.latestVersion.display}：${checkState.info.releaseTitle}",
                        "New version ${checkState.info.latestVersion.display}: ${checkState.info.releaseTitle}"
                    )
                is UpdateCheckState.Failed -> if (state.manualErrorVisible) {
                    noteLinkText("检查失败：${checkState.message}", "Check failed: ${checkState.message}")
                } else {
                    noteLinkText("自动检查暂时不可用", "Automatic check is temporarily unavailable")
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
                Text(
                    if (checkState is UpdateCheckState.Checking) {
                        noteLinkText(
                            "检查中",
                            "Checking"
                        )
                    } else {
                        noteLinkText("检查更新", "Check for updates")
                    }
                )
            }
            (checkState as? UpdateCheckState.UpdateAvailable)?.let { available ->
                Button(
                    onClick = { onOpenRelease(available.info.releaseUrl) }
                ) { Text(noteLinkText("查看 Release", "View release")) }
            }
        }
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
    return Intent.createChooser(send, noteLinkText("分享导出", "Share export")).apply {
        clipData = send.clipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@Suppress("LongParameterList")
@Composable
private fun DeviceCard(
    devices: List<PairedDevice>,
    selectedDeviceId: String?,
    connectionStatus: String,
    showPermissionButton: Boolean,
    onPair: () -> Unit,
    onSelect: (String) -> Unit,
    onUnpair: (String) -> Unit,
    onPermissions: () -> Unit,
    onRefresh: () -> Unit,
    receiveEnabled: Boolean,
    onReceiveToggle: () -> Unit
) {
    var deviceMenuExpanded by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.id == selectedDeviceId }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (devices.isEmpty()) {
                    noteLinkText(
                        "尚未配对",
                        "Not paired"
                    )
                } else {
                    noteLinkText("已配对设备 · ${devices.size}", "Paired devices · ${devices.size}")
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(connectionStatus)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    DeviceDropdown(
                        devices,
                        selectedDeviceId,
                        selected?.name,
                        deviceMenuExpanded,
                        { deviceMenuExpanded = it },
                        onPair,
                        onSelect,
                        onUnpair,
                    )
                }
                IconButton(
                    onClick = onRefresh
                ) { Icon(Icons.Default.Refresh, noteLinkText("刷新连接状态", "Refresh connection")) }
            }
            if (showPermissionButton || devices.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showPermissionButton) {
                        OutlinedButton(
                            onClick = onPermissions,
                            modifier = Modifier.weight(1f)
                        ) { Text(noteLinkText("授予权限", "Grant permission")) }
                    }
                    if (devices.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onReceiveToggle,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (receiveEnabled) {
                                    noteLinkText(
                                        "暂停接收",
                                        "Pause receiving"
                                    )
                                } else {
                                    noteLinkText("恢复接收", "Resume receiving")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun DeviceDropdown(
    devices: List<PairedDevice>,
    selectedDeviceId: String?,
    selectedDeviceName: String?,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
    onPair: () -> Unit,
    onSelect: (String) -> Unit,
    onUnpair: (String) -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { setExpanded(true) }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedDeviceName ?: noteLinkText("选择设备", "Select device"), modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, noteLinkText("展开设备列表", "Expand device list"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(device.name)
                            Text(device.id.takeLast(8), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        setExpanded(false)
                        onSelect(device.id)
                    },
                    leadingIcon = {
                        if (device.id == selectedDeviceId) {
                            Icon(
                                Icons.Default.Check,
                                noteLinkText("当前设备", "Current device")
                            )
                        }
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            setExpanded(false)
                            onUnpair(device.id)
                        }) { Icon(Icons.Default.Delete, noteLinkText("解绑 ${device.name}", "Unpair ${device.name}")) }
                    },
                )
            }
            if (devices.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(noteLinkText("新增设备", "Add device")) },
                onClick = {
                    setExpanded(false)
                    onPair()
                },
                leadingIcon = { Icon(Icons.Default.Add, null) },
            )
        }
    }
}

@Composable private fun QueueCard(item: QueueItem, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (item.kind == ContentKind.IMAGE) noteLinkText("图片", "Image") else noteLinkText("文字", "Text"),
                    fontWeight = FontWeight.Bold
                )
                Text(item.displayName.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${item.state} · ${formatSize(item.byteLength)}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onUp) { Icon(Icons.Default.KeyboardArrowUp, noteLinkText("上移", "Move up")) }
            IconButton(onClick = onDown) { Icon(Icons.Default.KeyboardArrowDown, noteLinkText("下移", "Move down")) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, noteLinkText("删除", "Delete")) }
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
                    InboxExportState.RECEIVING -> noteLinkText(
                        "接收中 · ${formatSize(item.byteLength)}",
                        "Receiving · ${formatSize(item.byteLength)}"
                    )
                    InboxExportState.COMPLETE -> noteLinkText(
                        "已接收 · ${formatSize(item.byteLength)}",
                        "Received · ${formatSize(item.byteLength)}"
                    )
                    InboxExportState.FAILED -> noteLinkText(
                        "接收失败 · ${item.error.orEmpty()}",
                        "Receive failed · ${item.error.orEmpty()}"
                    )
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.state == InboxExportState.COMPLETE) {
                    IconButton(
                        onClick = onOpen
                    ) { Icon(Icons.AutoMirrored.Filled.OpenInNew, noteLinkText("打开", "Open")) }
                    IconButton(onClick = onShare) { Icon(Icons.Default.Share, noteLinkText("分享", "Share")) }
                    IconButton(
                        onClick = onSave
                    ) { Icon(Icons.Default.Download, noteLinkText("保存到 Downloads", "Save to Downloads")) }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, noteLinkText("删除", "Delete")) }
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
    if (!context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_BLUETOOTH_LE
        )
    ) {
        return noteLinkText("此手机不支持 BLE", "This phone does not support BLE")
    }
    if (!context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_WIFI_DIRECT
        )
    ) {
        return noteLinkText("此手机不支持 Wi-Fi Direct", "This phone does not support Wi-Fi Direct")
    }
    if (!permissionsGranted) return noteLinkText("需要附近设备权限", "Nearby devices permission required")
    if (context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) {
        return noteLinkText(
            "蓝牙已关闭",
            "Bluetooth is off"
        )
    }
    if (context.getSystemService(WifiManager::class.java)?.isWifiEnabled != true) {
        return noteLinkText(
            "WLAN 已关闭",
            "Wi-Fi is off"
        )
    }
    if (!isPaired) return noteLinkText("生成配对码后在 Note 上输入", "Generate a pairing code and enter it on Note")
    if (!receiveEnabled) return noteLinkText("已暂停接收来自 Note 的导出", "Receiving exports from Note is paused")
    return if (hasItems) {
        noteLinkText(
            "已就绪，正在等待 Note 获取",
            "Ready; waiting for Note"
        )
    } else {
        noteLinkText("已就绪，暂无待发送内容", "Ready; nothing queued")
    }
}

/** Repository intentionally owns application context; this avoids leaking the Activity to launchers. */
private fun queueContext(queue: PhoneQueueRepository): Context {
    return queue.appContext
}

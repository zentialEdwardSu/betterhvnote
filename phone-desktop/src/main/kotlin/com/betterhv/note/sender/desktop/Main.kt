package com.betterhv.note.sender.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.betterhv.note.sender.shared.DashboardActions
import com.betterhv.note.sender.shared.DashboardItem
import com.betterhv.note.sender.shared.DashboardItemState
import com.betterhv.note.sender.shared.DashboardState
import com.betterhv.note.sender.shared.NoteLinkDashboard
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.QueueState
import com.betterhv.transfer.windows.JnaWindowsNativeApi
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

fun main() {
    DesktopLog.info("app.start", "os=${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    val native = runCatching { JnaWindowsNativeApi.load() }
        .onSuccess { DesktopLog.info("native.loaded") }
        .onFailure { DesktopLog.error("native.load", it) }
        .getOrThrow()

    application {
    val controller = remember { DesktopAppController(native) }
    var windowVisible by remember { mutableStateOf(true) }
    @Suppress("DEPRECATION")
    val icon = painterResource("icons/notelink.png")

    Tray(
        icon = icon,
        tooltip = "NoteLink",
        onAction = { windowVisible = true },
        menu = {
            Item("Open NoteLink", onClick = { windowVisible = true })
            if (controller.state.value.receiveEnabled) {
                Item("Pause receiving", onClick = { controller.setReceiveEnabled(false) })
            } else {
                Item("Resume receiving", onClick = { controller.setReceiveEnabled(true) })
            }
            Separator()
            Item("Exit", onClick = { controller.close(); exitApplication() })
        }
    )

        Window(
        visible = windowVisible,
        onCloseRequest = { windowVisible = false },
        title = "NoteLink",
        icon = icon,
        state = rememberWindowState(width = 860.dp, height = 680.dp)
    ) {
        DisposableEffect(window) {
            val target = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    runCatching {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        when {
                            event.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                                @Suppress("UNCHECKED_CAST")
                                controller.addFiles(event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                            }
                            event.transferable.isDataFlavorSupported(DataFlavor.imageFlavor) ->
                                controller.addImage(event.transferable.getTransferData(DataFlavor.imageFlavor) as Image)
                            event.transferable.isDataFlavorSupported(DataFlavor.stringFlavor) ->
                                controller.addText(event.transferable.getTransferData(DataFlavor.stringFlavor) as String)
                            else -> error("不支持的拖入内容")
                        }
                        event.dropComplete(true)
                    }.onFailure {
                        event.dropComplete(false)
                    }
                }
            }, true)
            onDispose { window.dropTarget = null; target.removeNotify() }
        }
        val state by controller.state.collectAsState()
        NoteLinkDashboard(state, DashboardActions(
            addFiles = { chooseFiles(window)?.let(controller::addFiles) },
            addText = controller::addText,
            pasteClipboard = controller::addClipboard,
            deleteQueueItem = controller::deleteQueueItem,
            openInboxItem = controller::openInboxItem,
            saveInboxItem = { id -> chooseSaveLocation(window, controller.inboxFile(id))?.let { controller.saveInboxItem(id, it) } },
            deleteInboxItem = controller::deleteInboxItem,
            setReceiveEnabled = controller::setReceiveEnabled,
            saveDisplayName = controller::saveDisplayName,
            beginPairing = controller::beginPairing,
            unpair = controller::unpair,
            refreshStatus = controller::refreshStatus,
            cancelTransfer = controller::cancelTransfer,
            setShowRecentTransferEvents = controller::setShowRecentTransferEvents,
            dragInboxItem = { id -> desktopInboxDragModifier(controller.inboxFile(id)) }
        ))
    }
    }
}

private class DesktopAppController(private val native: JnaWindowsNativeApi) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paths = DesktopPaths()
    private val settings = DesktopSettings(paths, native)
    private val queue = DesktopQueueRepository(paths)
    private val inbox = DesktopInboxRepository(paths)
    private val notice = MutableStateFlow<String?>(null)
    private val mutableState = MutableStateFlow(DashboardState())
    private val pairingRevision = MutableStateFlow(0)
    private val transfer = DesktopTransferService(native, settings, queue, inbox) { pairingRevision.value++ }
    val state: StateFlow<DashboardState> = mutableState

    init {
        scope.launch {
            combine(queue.itemFlow, inbox.itemFlow, transfer.status, notice, pairingRevision) { queued, received, status, currentNotice, _ ->
                val pairing = settings.pairing
                DashboardState(
                    displayName = settings.displayName,
                    status = status.summary,
                    statusDetail = status.detail,
                    transfer = status.transfer,
                    transferLog = status.transferLog,
                    showRecentTransferEvents = settings.showRecentTransferEvents,
                    receiveEnabled = settings.receiveEnabled,
                    pairedDeviceName = pairing?.deviceName,
                    pairingCode = if (pairing == null) settings.ownerPairingCode else null,
                    queue = queued.map { item ->
                        DashboardItem(
                            item.id.toString(), item.displayName ?: if (item.kind == ContentKind.TEXT) "文字" else "图片",
                            "${item.kind.label()} · ${formatBytes(item.byteLength)} · ${item.state.label()}",
                            when (item.state) {
                                QueueState.TRANSFERRING, QueueState.AWAITING_COMMIT -> DashboardItemState.TRANSFERRING
                                QueueState.FAILED -> DashboardItemState.FAILED
                                else -> DashboardItemState.PENDING
                            }
                        )
                    },
                    inbox = received.map { item ->
                        DashboardItem(
                            item.artifactId.toString(), item.displayName,
                            "${formatBytes(item.byteLength)} · ${item.state.name.lowercase()}",
                            when (item.state) {
                                DesktopInboxState.COMPLETE -> DashboardItemState.COMPLETE
                                DesktopInboxState.FAILED -> DashboardItemState.FAILED
                                DesktopInboxState.RECEIVING -> DashboardItemState.TRANSFERRING
                            }
                        )
                    },
                    notice = currentNotice
                )
            }.collect(mutableState)
        }
        if (settings.receiveEnabled) transfer.start()
        else mutableState.value = mutableState.value.copy(receiveEnabled = false, status = "接收已暂停")
    }

    fun addFiles(files: List<File>) = scope.launch {
        runCatching { queue.enqueueFiles(files, settings.pairing?.deviceId) }
            .onSuccess { transfer.refreshCounts(); notice.value = "已加入 ${it.size} 个文件" }
            .onFailure { notice.value = it.message ?: "添加文件失败" }
    }

    fun addText(value: String) = scope.launch {
        runCatching { queue.enqueueText(value, settings.pairing?.deviceId) }
            .onSuccess { transfer.refreshCounts(); notice.value = "文字已加入发送队列" }
            .onFailure { notice.value = it.message ?: "文字加入失败" }
    }

    fun addClipboard() = scope.launch {
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            when {
                clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
                    addImageToQueue(clipboard.getData(DataFlavor.imageFlavor) as Image)
                    "图片已加入发送队列"
                }
                clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> {
                    @Suppress("UNCHECKED_CAST")
                    val files = clipboard.getData(DataFlavor.javaFileListFlavor) as List<File>
                    queue.enqueueFiles(files, settings.pairing?.deviceId)
                    "图片已加入发送队列"
                }
                clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor) -> {
                    queue.enqueueText(clipboard.getData(DataFlavor.stringFlavor) as String, settings.pairing?.deviceId)
                    "文字已加入发送队列"
                }
                else -> error("剪贴板没有可用文字或图片")
            }
        }.onSuccess { transfer.refreshCounts(); notice.value = it }
            .onFailure { notice.value = it.message ?: "剪贴板内容加入失败" }
    }

    fun addImage(image: Image) = scope.launch {
        runCatching { addImageToQueue(image) }
            .onSuccess { transfer.refreshCounts(); notice.value = "图片已加入发送队列" }
            .onFailure { notice.value = it.message ?: "图片加入失败" }
    }

    private fun addImageToQueue(image: Image) {
        val file = File(paths.outbox, "clipboard-${UUID.randomUUID()}.png")
        val buffered = BufferedImage(image.getWidth(null).coerceAtLeast(1), image.getHeight(null).coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        ImageIO.write(buffered, "png", file)
        try {
            queue.enqueueFiles(listOf(file), settings.pairing?.deviceId)
        } finally {
            file.delete()
        }
    }

    fun deleteQueueItem(id: String) = scope.launch {
        runCatching { queue.delete(UUID.fromString(id)) }
        transfer.refreshCounts()
    }

    fun openInboxItem(id: String) = scope.launch {
        runCatching {
            val file = requireNotNull(inbox.find(UUID.fromString(id))?.file?.takeIf(File::isFile))
            require(Desktop.isDesktopSupported())
            Desktop.getDesktop().open(file)
        }.onFailure { notice.value = it.message ?: "无法打开文件" }
    }

    fun inboxFile(id: String): File? = runCatching { inbox.find(UUID.fromString(id))?.file }.getOrNull()

    fun saveInboxItem(id: String, destination: File) = scope.launch {
        runCatching {
            val source = requireNotNull(inboxFile(id)?.takeIf(File::isFile))
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onSuccess { notice.value = "文件已保存" }.onFailure { notice.value = it.message ?: "保存失败" }
    }

    fun deleteInboxItem(id: String) = scope.launch { runCatching { inbox.delete(UUID.fromString(id)) } }

    fun setReceiveEnabled(enabled: Boolean) {
        settings.receiveEnabled = enabled
        if (enabled) transfer.start() else transfer.stop()
    }

    fun saveDisplayName(name: String) {
        runCatching { settings.displayName = name; transfer.restart() }
            .onSuccess { notice.value = "显示名称已保存" }
            .onFailure { notice.value = it.message ?: "名称无效" }
    }

    fun beginPairing() {
        runCatching { settings.beginOwnerPairing(); pairingRevision.value++; transfer.restart() }
            .onSuccess { notice.value = "新的配对码已生成" }
            .onFailure { notice.value = it.message ?: "生成配对码失败" }
    }

    fun unpair() {
        transfer.stop()
        settings.unpair()
        pairingRevision.value++
        notice.value = "已取消配对；队列和收件箱保留"
    }

    fun refreshStatus() {
        pairingRevision.value++
        if (settings.receiveEnabled && !transfer.status.value.running) transfer.start()
    }

    fun cancelTransfer() = transfer.cancel()

    fun setShowRecentTransferEvents(show: Boolean) {
        settings.showRecentTransferEvents = show
        pairingRevision.value++
    }

    override fun close() {
        DesktopLog.info("app.stop")
        transfer.close()
        inbox.close()
        queue.close()
        scope.cancel()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@androidx.compose.runtime.Composable
private fun desktopInboxDragModifier(file: File?): Modifier {
    if (file == null || !file.isFile) return Modifier
    val transferData = remember(file.absolutePath, file.lastModified()) {
        DragAndDropTransferData(
            DragAndDropTransferable(FileTransferable(file)),
            listOf(DragAndDropTransferAction.Copy)
        )
    }
    return Modifier.dragAndDropSource(
        drawDragDecoration = {},
        block = {
            var started = false
            detectDragGestures { change, _ ->
                change.consume()
                if (!started) {
                    started = true
                    startTransfer(transferData)
                }
            }
        }
    )
}

private class FileTransferable(private val file: File) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        require(isDataFlavorSupported(flavor))
        return listOf(file)
    }
}

private fun chooseFiles(owner: java.awt.Window): List<File>? {
    val dialog = FileDialog(owner as? Frame, "添加图片", FileDialog.LOAD).apply {
        isMultipleMode = true
        filenameFilter = java.io.FilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp") }
        isVisible = true
    }
    return dialog.files.toList().takeIf(List<File>::isNotEmpty)
}

private fun chooseSaveLocation(owner: java.awt.Window, source: File?): File? {
    source ?: return null
    val dialog = FileDialog(owner as? Frame, "另存为", FileDialog.SAVE).apply { file = source.name; isVisible = true }
    return dialog.file?.let { File(dialog.directory, it) }
}

private fun ContentKind.label() = if (this == ContentKind.IMAGE) "图片" else "文字"
private fun QueueState.label() = when (this) {
    QueueState.PENDING -> "等待发送"
    QueueState.LEASED -> "已连接"
    QueueState.TRANSFERRING -> "正在发送"
    QueueState.AWAITING_COMMIT -> "等待确认"
    QueueState.FAILED -> "发送失败"
}
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

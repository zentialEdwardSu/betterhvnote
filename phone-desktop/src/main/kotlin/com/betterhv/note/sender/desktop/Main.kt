package com.betterhv.note.sender.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
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
import com.betterhv.note.sender.shared.DashboardHistoryItem
import com.betterhv.note.sender.shared.DashboardItem
import com.betterhv.note.sender.shared.DashboardItemState
import com.betterhv.note.sender.shared.DashboardPairedDevice
import com.betterhv.note.sender.shared.DashboardPairing
import com.betterhv.note.sender.shared.DashboardState
import com.betterhv.note.sender.shared.NoteLinkDashboard
import com.betterhv.note.sender.shared.NoteLinkI18n
import com.betterhv.note.sender.shared.NoteLinkLanguage
import com.betterhv.note.sender.shared.noteLinkText
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.QueueItem
import com.betterhv.transfer.core.QueueState
import com.betterhv.transfer.core.SendOrder
import com.betterhv.transfer.core.SendOrderSelector
import com.betterhv.transfer.core.SendOutcome
import com.betterhv.transfer.windows.JnaWindowsNativeApi
import com.betterhv.update.UpdateCheckState
import com.betterhv.update.UpdateChecker
import com.betterhv.update.UpdateProduct
import com.betterhv.update.UpdateUiState
import com.betterhv.update.NoteAppUpdateTaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

fun main() {
  DesktopLog.info(
    "app.start",
    "os=${System.getProperty("os.name")} ${System.getProperty("os.version")}",
  )
  val paths = DesktopPaths()
  val guard = SingleInstanceGuard(paths.root)
  if (!guard.tryAcquire()) {
    DesktopLog.info("app.second-instance", "signaled=${guard.signalExisting()}")
    guard.close()
    return
  }
  try {
    runNoteLink(guard)
  } finally {
    guard.close()
  }
}

@Suppress("LongMethod")
private fun runNoteLink(guard: SingleInstanceGuard) {
  val native = runCatching { JnaWindowsNativeApi.load() }
    .onSuccess { DesktopLog.info("native.loaded") }
    .onFailure { DesktopLog.error("native.load", it) }
    .getOrThrow()

  application {
    val controller = remember { DesktopAppController(native) }
    val firewallChecker = remember { WindowsFirewallRuleChecker() }
    val firewallInstaller = remember { WindowsFirewallInstaller() }
    val uiScope = rememberCoroutineScope()
    var windowVisible by remember { mutableStateOf(true) }
    var firewallWarning by remember { mutableStateOf<WindowsFirewallRuleStatus?>(null) }
    var firewallChecking by remember { mutableStateOf(false) }
    var firewallInstalling by remember { mutableStateOf(false) }
    var firewallMessage by remember { mutableStateOf<String?>(null) }
    var firewallDismissed by remember { mutableStateOf(false) }

    @Suppress("DEPRECATION")
    val icon = painterResource("icons/notelink.png")

    val applyFirewallStatus: (WindowsFirewallRuleStatus) -> Unit = { status ->
      when (status) {
        WindowsFirewallRuleStatus.Configured -> firewallWarning = null
        is WindowsFirewallRuleStatus.CheckFailed -> {
          DesktopLog.info("firewall.unverified", status.detail)
          firewallWarning = null
          controller.reportFirewallUnverified(status.detail)
        }

        else -> firewallWarning = status
      }
    }
    val refreshFirewallStatus = {
      firewallDismissed = false
      firewallMessage = null
      firewallChecking = true
      uiScope.launch {
        val status = withContext(Dispatchers.IO) { firewallChecker.check() }
        applyFirewallStatus(status)
        firewallChecking = false
      }
      Unit
    }
    val installFirewall = {
      firewallInstalling = true
      firewallMessage = null
      uiScope.launch {
        val result = withContext(Dispatchers.IO) { firewallInstaller.install() }
        firewallInstalling = false
        when (result) {
          FirewallInstallResult.Installed -> {
            DesktopLog.info("firewall.install", "installed")
            refreshFirewallStatus()
          }

          FirewallInstallResult.Cancelled -> {
            DesktopLog.info("firewall.install", "cancelled")
            firewallMessage =
              noteLinkText("已取消管理员授权", "Administrator approval was cancelled")
          }

          is FirewallInstallResult.Failed -> {
            DesktopLog.info("firewall.install", "failed=${result.detail}")
            firewallMessage =
              noteLinkText("启用失败：${result.detail}", "Could not enable: ${result.detail}")
          }
        }
      }
      Unit
    }
    LaunchedEffect(Unit) {
      val status = withContext(Dispatchers.IO) { firewallChecker.check() }
      DesktopLog.info("firewall.check", status::class.simpleName ?: "unknown")
      applyFirewallStatus(status)
    }

    val mainWindow = remember { mutableStateOf<ComposeWindow?>(null) }
    LaunchedEffect(Unit) {
      guard.startListening {
        SwingUtilities.invokeLater {
          windowVisible = true
          mainWindow.value?.toFront()
          mainWindow.value?.requestFocus()
        }
      }
    }

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
        Item(
          "Exit",
          onClick = {
            controller.close();
            guard.close();
            exitApplication()
          },
        )
      },
    )

    Window(
      visible = windowVisible,
      onCloseRequest = { windowVisible = false },
      title = "NoteLink",
      icon = icon,
      state = rememberWindowState(width = 860.dp, height = 680.dp),
    ) {
      DisposableEffect(window) {
        mainWindow.value = window
        val target = DropTarget(
          window, DnDConstants.ACTION_COPY,
          object : DropTargetAdapter() {
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

                  else -> error("Unsupported dropped content")
                }
                event.dropComplete(true)
              }.onFailure {
                event.dropComplete(false)
              }
            }
          },
          true,
        )
        onDispose {
          mainWindow.value = null
          window.dropTarget = null;
          target.removeNotify()
        }
      }
      val state by controller.state.collectAsState()
      NoteLinkDashboard(state, controller.dashboardActions(window))
      firewallWarning?.let { status ->
        if (!firewallDismissed) {
          FirewallWarningDialog(
            status = status,
            checking = firewallChecking,
            installing = firewallInstalling,
            message = firewallMessage,
            onInstall = installFirewall,
            onOpenDirectory = {
              runCatching {
                val directory = requireNotNull(findPortableDirectory()) {
                  "Cannot find the firewall script directory"
                }
                require(Desktop.isDesktopSupported()) {
                  "Opening a directory is not supported"
                }
                Desktop.getDesktop().open(directory)
              }.onFailure {
                firewallWarning = WindowsFirewallRuleStatus.CheckFailed(
                  it.message ?: "Cannot open the script directory",
                )
              }
            },
            onRetry = refreshFirewallStatus,
            onDismiss = {
              firewallDismissed = true
              firewallWarning = null
            },
          )
        }
      }
    }
  }
}

private class DesktopAppController(private val native: JnaWindowsNativeApi) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val paths = DesktopPaths()
  private val settings = DesktopSettings(paths, native)

  init {
    NoteLinkI18n.language = settings.language
  }

  private val queue = DesktopQueueRepository(
    paths,
    sendOrder = { settings.sendOrder },
    deviceName = { id ->
      id?.let { deviceId -> settings.pairings.firstOrNull { it.deviceId == deviceId }?.deviceName }
    },
  )
  private val inbox = DesktopInboxRepository(paths)
  private val notice = MutableStateFlow<String?>(null)
  private val updateUiState = MutableStateFlow(UpdateUiState())
  private val notePackageStatus = MutableStateFlow<String?>(null)
  private val updateChecker = UpdateChecker()
  private val appVersion = loadNoteLinkVersion()
  private val mutableState = MutableStateFlow(DashboardState())
  private val pairingRevision = MutableStateFlow(0)
  private val transfer = DesktopTransferService(native, settings, queue, inbox, paths.updateCache) {
    pairingRevision.value++
  }
  val state: StateFlow<DashboardState> = mutableState

  fun dashboardActions(owner: java.awt.Window) = DashboardActions(
    addFiles = { chooseFiles(owner)?.let(::addFiles) },
    addText = ::addText,
    pasteClipboard = ::addClipboard,
    deleteQueueItem = ::deleteQueueItem,
    resendHistory = ::resendHistory,
    deleteHistory = ::deleteHistory,
    clearHistory = ::clearHistory,
    setSendOrder = ::setSendOrder,
    openInboxItem = ::openInboxItem,
    saveInboxItem = { id ->
      chooseSaveLocation(owner, inboxFile(id))?.let {
        saveInboxItem(
          id,
          it,
        )
      }
    },
    deleteInboxItem = ::deleteInboxItem,
    setReceiveEnabled = ::setReceiveEnabled,
    saveDisplayName = ::saveDisplayName,
    beginPairing = ::beginPairing,
    submitPairingCode = transfer::submitPairingCode,
    cancelPairing = transfer::cancelPairing,
    selectDevice = ::selectDevice,
    unpair = ::unpair,
    refreshStatus = ::refreshStatus,
    cancelTransfer = ::cancelTransfer,
    setShowRecentTransferEvents = ::setShowRecentTransferEvents,
    setLanguage = ::setLanguage,
    checkForUpdates = { checkForUpdates(manual = true) },
    openRelease = ::openRelease,
    dismissUpdate = ::dismissUpdate,
    importNotePackage = { chooseApk(owner)?.let(::importNotePackage) },
    dragInboxItem = { id -> desktopInboxDragModifier(inboxFile(id)) },
  )

  init {
    scope.launch {
      transfer.noteUpdateState.collect { state ->
        if (state != null) notePackageStatus.value = state.desktopText()
      }
    }
    scope.launch {
      val revisionAndUpdate =
        combine(pairingRevision, updateUiState, notePackageStatus, transfer.pairingState) {
            _, update, packageStatus, _ ->
          update to packageStatus
        }
      val queueAndHistory = combine(queue.itemFlow, queue.historyFlow) { items, history ->
        items to history
      }
      combine(queueAndHistory, inbox.itemFlow, transfer.status, notice, revisionAndUpdate) {
          queuedAndHistory,
          received,
          status,
          currentNotice,
          updateAndPackage,
        ->
        val (queued, historyEntries) = queuedAndHistory
        val pairings = settings.pairings
        val pairing = pairings.maxByOrNull(DesktopPairing::lastUsedAt)
        DashboardState(
          displayName = settings.displayName,
          status = status.summary,
          statusDetail = status.detail,
          transfer = status.transfer,
          transferLog = status.transferLog,
          timelineSummary = status.timelineSummary,
          showRecentTransferEvents = settings.showRecentTransferEvents,
          language = settings.language,
          receiveEnabled = settings.receiveEnabled,
          pairedDevices = pairings.map { DashboardPairedDevice(it.deviceId, it.deviceName) },
          selectedDeviceId = pairing?.deviceId,
          sendOrder = settings.sendOrder,
          nextQueueItemId = nextQueueItemId(queued, settings.sendOrder, pairing?.deviceId),
          pairing = when (val pairingState = transfer.pairingState.value) {
            DesktopPairingState.Idle -> null
            DesktopPairingState.Entering -> DashboardPairing()
            is DesktopPairingState.Waiting -> DashboardPairing(
              waitingCode = pairingState.code, message = pairingState.message
            )
            is DesktopPairingState.Paired -> DashboardPairing(pairedName = pairingState.deviceName)
            is DesktopPairingState.Failed -> DashboardPairing(message = pairingState.message)
          },
          queue = queued.map { item ->
            DashboardItem(
              item.id.toString(),
              item.displayName ?: if (item.kind == ContentKind.TEXT) {
                noteLinkText(
                  "文字",
                  "Text",
                )
              } else {
                noteLinkText("图片", "Image")
              },
              "${item.kind.label()} · ${formatBytes(item.byteLength)} · ${item.state.label()}",
              when (item.state) {
                QueueState.TRANSFERRING, QueueState.AWAITING_COMMIT -> DashboardItemState.TRANSFERRING
                QueueState.FAILED -> DashboardItemState.FAILED
                else -> DashboardItemState.PENDING
              },
            )
          },
          history = historyEntries.map { entry ->
            DashboardHistoryItem(
              id = entry.id.toString(),
              title = entry.displayName ?: entry.kind.label(),
              detail = listOfNotNull(
                if (entry.outcome == SendOutcome.FAILED) {
                  noteLinkText("失败", "Failed")
                } else {
                  noteLinkText("已发送", "Sent")
                },
                entry.destinationName ?: entry.destinationDeviceId,
                formatBytes(entry.byteLength),
                entry.failureReason,
              ).joinToString(" · "),
              failed = entry.outcome == SendOutcome.FAILED,
              resendable = queue.canResend(entry),
            )
          },
          inbox = received.map { item ->
            DashboardItem(
              item.artifactId.toString(),
              item.displayName,
              "${formatBytes(item.byteLength)} · ${item.state.name.lowercase()}",
              when (item.state) {
                DesktopInboxState.COMPLETE -> DashboardItemState.COMPLETE
                DesktopInboxState.FAILED -> DashboardItemState.FAILED
                DesktopInboxState.RECEIVING -> DashboardItemState.TRANSFERRING
              },
            )
          },
          notice = currentNotice,
          appVersion = appVersion,
          updateUiState = updateAndPackage.first,
          notePackageStatus = updateAndPackage.second,
        )
      }.collect(mutableState)
    }
    if (settings.receiveEnabled) {
      transfer.start()
    } else {
      mutableState.value = mutableState.value.copy(
        receiveEnabled = false,
        status = noteLinkText("接收已暂停", "Receiving paused"),
      )
    }
    checkForUpdates(manual = false)
  }

  fun addFiles(files: List<File>) = scope.launch {
    runCatching { queue.enqueueFiles(files, settings.pairing?.deviceId) }
      .onSuccess {
        transfer.refreshCounts();
        notice.value = noteLinkText(
          "已加入 ${it.size} 个文件",
          "Added ${it.size} files",
        )
      }
      .onFailure { notice.value = it.message ?: "Could not add files" }
  }

  fun addText(value: String) = scope.launch {
    runCatching { queue.enqueueText(value, settings.pairing?.deviceId) }
      .onSuccess {
        transfer.refreshCounts();
        notice.value = noteLinkText("文字已加入发送队列", "Text added to send queue")
      }
      .onFailure { notice.value = it.message ?: "Could not add text" }
  }

  fun importNotePackage(file: File) = scope.launch {
    notePackageStatus.value = noteLinkText("正在校验 APK…", "Verifying APK...")
    runCatching { transfer.importNotePackage(file) }
      .onSuccess { version ->
        notePackageStatus.value =
          noteLinkText("已验证 BetterHvNote $version", "Verified BetterHvNote $version")
      }
      .onFailure { notePackageStatus.value = it.message ?: "Could not import Note APK" }
  }

  fun addClipboard() = scope.launch {
    runCatching {
      val clipboard = Toolkit.getDefaultToolkit().systemClipboard
      when {
        clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
          addImageToQueue(clipboard.getData(DataFlavor.imageFlavor) as Image)
          noteLinkText("图片已加入发送队列", "Image added to send queue")
        }

        clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> {
          @Suppress("UNCHECKED_CAST")
          val files = clipboard.getData(DataFlavor.javaFileListFlavor) as List<File>
          queue.enqueueFiles(files, settings.pairing?.deviceId)
          noteLinkText("图片已加入发送队列", "Image added to send queue")
        }

        clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor) -> {
          queue.enqueueText(
            clipboard.getData(DataFlavor.stringFlavor) as String,
            settings.pairing?.deviceId,
          )
          noteLinkText("文字已加入发送队列", "Text added to send queue")
        }

        else -> error("Clipboard contains no usable text or image")
      }
    }.onSuccess {
      transfer.refreshCounts();
      notice.value = it
    }
      .onFailure { notice.value = it.message ?: "Could not add clipboard content" }
  }

  fun addImage(image: Image) = scope.launch {
    runCatching { addImageToQueue(image) }
      .onSuccess {
        transfer.refreshCounts();
        notice.value = noteLinkText("图片已加入发送队列", "Image added to send queue")
      }
      .onFailure { notice.value = it.message ?: "Could not add image" }
  }

  private fun addImageToQueue(image: Image) {
    val file = File(paths.outbox, "clipboard-${UUID.randomUUID()}.png")
    val buffered = BufferedImage(
      image.getWidth(null).coerceAtLeast(1),
      image.getHeight(null).coerceAtLeast(1),
      BufferedImage.TYPE_INT_ARGB,
    )
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

  /** Surfaces a non-blocking warning when Windows denies reading firewall state. */
  fun reportFirewallUnverified(detail: String) {
    notice.value = noteLinkText(
      "无法验证防火墙规则（$detail）；规则可能已存在，若传输失败请运行便携包内的诊断脚本。",
      "Could not verify the firewall rule ($detail). The rule may still exist; if transfers fail, " +
        "run the bundled diagnostics script.",
    )
  }

  fun resendHistory(id: String) = scope.launch {
    runCatching {
      val pairing = settings.pairing
      requireNotNull(
        queue.requeueHistory(
          UUID.fromString(id),
          pairing?.deviceId,
          settings.pairings.map { it.deviceId }.toSet(),
        ),
      ) {
        noteLinkText(
          "历史内容已清理，无法重新发送",
          "The retained content was pruned; cannot send again",
        )
      }
    }
      .onSuccess {
        transfer.refreshCounts()
        notice.value = noteLinkText("已重新加入发送队列", "Added back to the send queue")
      }
      .onFailure { notice.value = it.message ?: "Could not resend history item" }
  }

  fun deleteHistory(id: String) = scope.launch {
    runCatching { queue.deleteHistory(UUID.fromString(id)) }
  }

  fun clearHistory() = scope.launch { queue.clearHistory() }

  fun setSendOrder(order: SendOrder) {
    settings.sendOrder = order
    pairingRevision.value++
    notice.value = if (order == SendOrder.QUEUE) {
      noteLinkText("发送顺序：队列（最早优先）", "Send order: queue (oldest first)")
    } else {
      noteLinkText("发送顺序：栈（最新优先）", "Send order: stack (newest first)")
    }
    DesktopLog.info("queue.order", order.name)
  }

  fun openInboxItem(id: String) = scope.launch {
    runCatching {
      val file = requireNotNull(inbox.find(UUID.fromString(id))?.file?.takeIf(File::isFile))
      require(Desktop.isDesktopSupported())
      Desktop.getDesktop().open(file)
    }.onFailure { notice.value = it.message ?: "Cannot open file" }
  }

  fun inboxFile(id: String): File? =
    runCatching { inbox.find(UUID.fromString(id))?.file }.getOrNull()

  fun saveInboxItem(id: String, destination: File) = scope.launch {
    runCatching {
      val source = requireNotNull(inboxFile(id)?.takeIf(File::isFile))
      Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }.onSuccess {
      notice.value = noteLinkText(
        "文件已保存",
        "File saved",
      )
    }.onFailure { notice.value = it.message ?: "Save failed" }
  }

  fun deleteInboxItem(id: String) =
    scope.launch { runCatching { inbox.delete(UUID.fromString(id)) } }

  fun setReceiveEnabled(enabled: Boolean) {
    settings.receiveEnabled = enabled
    if (enabled) transfer.start() else transfer.stop()
  }

  fun saveDisplayName(name: String) {
    runCatching {
      settings.displayName = name;
      transfer.restart()
    }
      .onSuccess { notice.value = noteLinkText("显示名称已保存", "Display name saved") }
      .onFailure { notice.value = it.message ?: "Invalid name" }
  }

  fun beginPairing() {
    if (!transfer.status.value.running && settings.receiveEnabled) transfer.start()
    transfer.openPairing()
  }

  fun selectDevice(deviceId: String) {
    settings.markLastUsed(deviceId)
    pairingRevision.value++
    notice.value = noteLinkText("已切换发送目标", "Send target changed")
  }

  fun unpair(deviceId: String) {
    transfer.stop()
    settings.unpair(deviceId)
    pairingRevision.value++
    if (settings.receiveEnabled) transfer.start()
    notice.value =
      noteLinkText("已解除设备配对；队列和收件箱保留", "Device unpaired; queue and inbox were kept")
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

  fun setLanguage(language: NoteLinkLanguage) {
    settings.language = language
    NoteLinkI18n.language = language
    pairingRevision.value++
  }

  fun checkForUpdates(manual: Boolean) {
    if (updateUiState.value.checkState is UpdateCheckState.Checking) return
    updateUiState.value = updateUiState.value.checking(manual)
    scope.launch {
      val result = updateChecker.check(UpdateProduct.NOTELINK, appVersion)
      updateUiState.value = updateUiState.value.completed(result, manual)
    }
  }

  fun dismissUpdate() {
    updateUiState.value = updateUiState.value.dismissBanner()
  }

  fun openRelease(url: String) {
    runCatching {
      require(UpdateChecker.isTrustedReleaseUrl(url)) { "Invalid release URL" }
      require(Desktop.isDesktopSupported()) { "Opening a browser is not supported" }
      Desktop.getDesktop().browse(URI(url))
    }.onFailure { notice.value = it.message ?: "Cannot open release page" }
  }

  override fun close() {
    DesktopLog.info("app.stop")
    transfer.close()
    inbox.close()
    queue.close()
    scope.cancel()
  }
}

@androidx.compose.runtime.Composable
private fun FirewallWarningDialog(
  status: WindowsFirewallRuleStatus,
  checking: Boolean,
  installing: Boolean,
  message: String?,
  onInstall: () -> Unit,
  onOpenDirectory: () -> Unit,
  onRetry: () -> Unit,
  onDismiss: () -> Unit,
) {
  val detail = when (status) {
    WindowsFirewallRuleStatus.Missing -> noteLinkText(
      "未找到 NoteLink TCP 39817 入站规则。",
      "The NoteLink TCP 39817 inbound rule was not found.",
    )

    is WindowsFirewallRuleStatus.Misconfigured -> noteLinkText(
      "规则存在但不正确：${status.fields.joinToString("、")}",
      "The NoteLink rule is incorrect: ${status.fields.joinToString(", ")}",
    )

    is WindowsFirewallRuleStatus.Blocked -> noteLinkText(
      "Windows 防火墙已禁止所有入站连接（配置文件：${status.profiles.joinToString("、")}）。" +
        "请在“高级安全 Windows Defender 防火墙 → 属性”中取消“阻止所有传入连接”，否则任何规则都不会生效。",
      "Windows Firewall is blocking all inbound connections " +
        "(profiles: ${status.profiles.joinToString(", ")}). Turn off \"Block all incoming connections\" " +
        "in Windows Defender Firewall advanced settings; otherwise every rule is ignored.",
    )

    is WindowsFirewallRuleStatus.CheckFailed -> noteLinkText(
      "无法确认防火墙规则：${status.detail}",
      "Could not verify the firewall rule: ${status.detail}",
    )

    WindowsFirewallRuleStatus.Configured -> return
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(noteLinkText("需要配置 Windows 防火墙", "Windows Firewall setup required")) },
    text = {
      androidx.compose.foundation.layout.Column {
        Text(detail)
        message?.let {
          Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
          Text(
            "netsh advfirewall firewall add rule name=NoteLink-TCP-39817 dir=in " +
              "action=allow protocol=TCP localport=39817 profile=any",
            modifier = Modifier.padding(top = 8.dp),
          )
        }
        Text(
          noteLinkText(
            "点击“启用规则”由应用提权创建规则；也可以打开程序目录手动运行脚本。",
            "Choose Enable rule to create it with an administrator prompt, or open the app folder and run the script.",
          ),
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    },
    confirmButton = {
      Button(onClick = onInstall, enabled = !checking && !installing) {
        Text(
          if (installing) {
            noteLinkText("正在启用…", "Enabling...")
          } else {
            noteLinkText("启用规则", "Enable rule")
          },
        )
      }
    },
    dismissButton = {
      androidx.compose.foundation.layout.Row {
        OutlinedButton(onClick = onOpenDirectory, enabled = !installing) {
          Text(noteLinkText("打开程序目录", "Open app folder"))
        }
        OutlinedButton(onClick = onRetry, enabled = !checking && !installing) {
          Text(
            if (checking) noteLinkText("检查中…", "Checking...") else noteLinkText(
              "重新检查",
              "Check again",
            ),
          )
        }
        TextButton(onClick = onDismiss) { Text(noteLinkText("稍后", "Later")) }
      }
    },
  )
}

private fun findPortableDirectory(): File? {
  val runtimeDirectory = File(System.getProperty("java.home"))
  val packagedRoot = runtimeDirectory.parentFile
  if (packagedRoot?.resolve("Allow-NoteLink-Firewall.ps1")?.isFile == true) return packagedRoot
  val developmentRoot = File(System.getProperty("user.dir"), "phone-desktop/src/main/portable")
  return developmentRoot.takeIf { it.resolve("Allow-NoteLink-Firewall.ps1").isFile }
}

private fun NoteAppUpdateTaskState.desktopText(): String = when (this) {
  NoteAppUpdateTaskState.Resolving -> noteLinkText(
    "正在查询 Note 正式版…",
    "Resolving the stable Note release...",
  )

  is NoteAppUpdateTaskState.Downloading -> noteLinkText(
    "正在下载 $version：$bytesDownloaded/$totalBytes",
    "Downloading $version: $bytesDownloaded/$totalBytes",
  )

  is NoteAppUpdateTaskState.UpToDate -> noteLinkText(
    "请求设备已是最新版本",
    "The requesting Note is up to date",
  )

  is NoteAppUpdateTaskState.Ready -> noteLinkText(
    "BetterHvNote $version 已验证并准备发送",
    "BetterHvNote $version is verified and ready",
  )

  is NoteAppUpdateTaskState.Failed -> noteLinkText(
    "准备更新失败：$message",
    "Update preparation failed: $message",
  )
}

private fun loadNoteLinkVersion(): String {
  val properties = Properties()
  val resource = Thread.currentThread().contextClassLoader
    .getResourceAsStream("notelink-version.properties")
  return requireNotNull(resource) { "Missing notelink-version.properties" }.use {
    properties.load(it)
    requireNotNull(properties.getProperty("version")) { "Missing NoteLink version" }
  }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@androidx.compose.runtime.Composable
private fun desktopInboxDragModifier(file: File?): Modifier {
  if (file == null || !file.isFile) return Modifier
  val transferData = remember(file.absolutePath, file.lastModified()) {
    DragAndDropTransferData(
      DragAndDropTransferable(FileTransferable(file)),
      listOf(DragAndDropTransferAction.Copy),
    )
  }
  return Modifier.dragAndDropSource(drawDragDecoration = {}) { transferData }
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
  val dialog =
    FileDialog(owner as? Frame, noteLinkText("添加文件", "Add files"), FileDialog.LOAD).apply {
      isMultipleMode = true
      filenameFilter = java.io.FilenameFilter { _, name ->
        name.substringAfterLast(
          '.',
          "",
        ).lowercase() in setOf("png", "jpg", "jpeg", "webp")
      }
      isVisible = true
    }
  return dialog.files.toList().takeIf(List<File>::isNotEmpty)
}

private fun chooseApk(owner: java.awt.Window): File? {
  val dialog = FileDialog(
    owner as? Frame,
    noteLinkText("导入 Note APK", "Import Note APK"),
    FileDialog.LOAD,
  ).apply {
    filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".apk", ignoreCase = true) }
    isVisible = true
  }
  return dialog.files.singleOrNull()
}

private fun chooseSaveLocation(owner: java.awt.Window, source: File?): File? {
  source ?: return null
  val dialog = FileDialog(
    owner as? Frame,
    noteLinkText("另存为", "Save as"),
    FileDialog.SAVE,
  ).apply {
    file = source.name;
    isVisible = true
  }
  return dialog.file?.let { File(dialog.directory, it) }
}

private fun ContentKind.label() = when (this) {
  ContentKind.IMAGE -> noteLinkText("图片", "Image")
  ContentKind.TEXT -> noteLinkText("文字", "Text")
  ContentKind.PDF -> "PDF"
  ContentKind.APP_PACKAGE -> noteLinkText("Note 安装包", "Note package")
}

private fun nextQueueItemId(items: List<QueueItem>, order: SendOrder, deviceId: String?): String? {
  val pending = items.filter {
    it.state == QueueState.PENDING && (it.destinationDeviceId == null || it.destinationDeviceId == deviceId)
  }
  return SendOrderSelector.ordered(pending, order).firstOrNull()?.id?.toString()
}

private fun QueueState.label() = when (this) {
  QueueState.PENDING -> noteLinkText("等待发送", "Pending")
  QueueState.LEASED -> noteLinkText("已连接", "Connected")
  QueueState.TRANSFERRING -> noteLinkText("正在发送", "Sending")
  QueueState.AWAITING_COMMIT -> noteLinkText("等待确认", "Awaiting confirmation")
  QueueState.FAILED -> "Send failed"
}

private fun formatBytes(bytes: Long): String = when {
  bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
  bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
  else -> "$bytes B"
}

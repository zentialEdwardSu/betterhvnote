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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betterhv.note.storage.StartupBehavior
import com.betterhv.note.toolbar.ToolbarCatalog
import com.betterhv.note.ui.EinkChoiceCell
import com.betterhv.note.ui.EinkColors
import com.betterhv.note.ui.EinkDimens
import com.betterhv.note.ui.EinkProgress
import com.betterhv.note.ui.EinkRadioMark
import com.betterhv.note.ui.EinkScreenHeader
import com.betterhv.note.ui.EinkTab
import com.betterhv.note.ui.EinkTheme
import com.betterhv.note.ui.EinkToggle
import com.betterhv.note.ui.EinkType
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
  NOTELINK,
  ;

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
  /** Code shown while waiting for a NoteLink user to type it; null when not pairing. */
  val pairingCode: String?,
  val pairingInProgress: Boolean,
  val pairingMessage: String?,
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
  val noteAppUpdateState: NoteAppUpdateUiState,
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
  val onShortcutBind: (ShortcutScene, HardwareKeyId, CommandBinding?) -> Unit,
  val onShortcutSceneClear: (ShortcutScene) -> Unit,
  val onStartPairing: () -> Unit,
  val onCancelPairing: () -> Unit,
  val onRenameClient: (String, String) -> Unit,
  val onUnpairClient: (String) -> Unit,
  val onTransferPermissions: () -> Unit,
  val onRefreshTransferStatus: () -> Unit,
  val onCancelTransfer: () -> Unit,
  val onCheckUpdate: () -> Unit,
  val onOpenRelease: (String) -> Unit,
  val onUpdateViaNoteLink: (String) -> Unit,
  val onContinueNoteAppInstall: () -> Unit,
  val onOpenBackup: () -> Unit,
  val onClose: () -> Unit,
)

@Composable
fun SettingsScreen(state: SettingsState, actions: SettingsActions) {
  EinkTheme { SettingsScreenContent(state, actions) }
}

@Composable
private fun SettingsScreenContent(state: SettingsState, actions: SettingsActions) {
  val debugMode = state.debugMode
  val startupBehavior = state.startupBehavior
  val pairedClients = state.pairedClients
  val onlineClients = state.onlineClients
  val pairingCode = state.pairingCode
  val pairingInProgress = state.pairingInProgress
  val pairingMessage = state.pairingMessage
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
  val noteAppUpdateState = state.noteAppUpdateState
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
  val onStartPairing = actions.onStartPairing
  val onCancelPairing = actions.onCancelPairing
  val onRenameClient = actions.onRenameClient
  val onUnpairClient = actions.onUnpairClient
  val onTransferPermissions = actions.onTransferPermissions
  val onRefreshTransferStatus = actions.onRefreshTransferStatus
  val onCancelTransfer = actions.onCancelTransfer
  val onCheckUpdate = actions.onCheckUpdate
  val onOpenRelease = actions.onOpenRelease
  val onUpdateViaNoteLink = actions.onUpdateViaNoteLink
  val onContinueNoteAppInstall = actions.onContinueNoteAppInstall
  val onClose = actions.onClose
  var renameTarget by remember { mutableStateOf<PairedDevice?>(null) }
  var shortcutScene by remember { mutableStateOf(ShortcutScene.EDITOR) }
  var shortcutKey by remember { mutableStateOf<HardwareKeyId?>(null) }
  var showOpenSourceLicenses by remember { mutableStateOf(false) }
  val uriHandler = LocalUriHandler.current
  var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.GENERAL) }
  LaunchedEffect(shortcutBindingRequest) {
    shortcutBindingRequest?.let { (scene, key) ->
      selectedTab = SettingsTab.SHORTCUTS
      shortcutScene = scene
      shortcutKey = key
      onShortcutBindingRequestConsumed()
    }
  }

  androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = EinkColors.Paper, shape = RectangleShape) {
    Column(Modifier.fillMaxSize()) {
      EinkScreenHeader(title = noteText("设置", "Settings"), onBack = onClose)
      Row(Modifier.fillMaxWidth()) {
        SettingsTab.entries.forEach { tab ->
          EinkTab(
            selected = selectedTab == tab,
            onClick = { selectedTab = tab },
            modifier = Modifier.weight(1f),
          ) {
            Text(tab.label(), style = EinkType.Label, maxLines = 1)
          }
        }
      }

      Box(Modifier.fillMaxWidth().weight(1f)) {
        when (selectedTab) {
          SettingsTab.GENERAL -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
          ) {
            Text(
              noteText("语言", "Language"),
              style = EinkType.SectionTitle,
              modifier = Modifier.padding(bottom = EinkDimens.SpaceSm),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(EinkDimens.SpaceSm)) {
              NoteLanguage.entries.forEach { option ->
                EinkChoiceCell(
                  modifier = Modifier.heightIn(min = EinkDimens.Touch),
                  selected = option == language,
                  contentDescription = option.displayName(),
                  onClick = { onLanguageChange(option) },
                ) {
                  Text(
                    (if (option == language) "✓ " else "") + option.displayName(),
                    style = EinkType.Body,
                    modifier = Modifier.padding(horizontal = EinkDimens.SpaceMd),
                  )
                }
              }
            }
            SettingRow(noteText("调试模式", "Debug mode"), onClick = { onDebugModeChange(!debugMode) }) {
              EinkToggle(checked = debugMode, contentDescription = noteText("调试模式", "Debug mode"))
            }
            SettingRow(
              noteText(
                "队列有内容时直接从 NoteLink 获取",
                "Get directly from NoteLink when its queue is not empty",
              ),
              onClick = {
                onSkipSourceSelectionChange(!skipSourceSelectionWhenQueueAvailable)
              }
            ) {
              EinkToggle(
                checked = skipSourceSelectionWhenQueueAvailable,
                contentDescription = noteText("从 NoteLink 获取", "Get from NoteLink"),
              )
            }
            SettingRow(noteText("末页按下一页时自动新增页面", "Create a page when navigating past the last page"), onClick = {
              onAutoCreatePageOnNextAtEndChange(!autoCreatePageOnNextAtEnd)
            }) {
              EinkToggle(
                checked = autoCreatePageOnNextAtEnd,
                contentDescription = noteText("自动新增页面", "Create page automatically"),
              )
            }
            Text(
              noteText("重新进入应用时", "When reopening the app"),
              style = EinkType.SectionTitle,
              modifier = Modifier.padding(top = EinkDimens.SpaceXl, bottom = EinkDimens.SpaceSm),
            )
            StartupBehavior.entries.forEach { behavior ->
              val label = when (behavior) {
                StartupBehavior.WORKING_COPY -> noteText("打开 Working Copy", "Open Working Copy")
                StartupBehavior.LAST_OPENED -> noteText("打开上次使用的笔记本", "Open the last notebook")
              }
              SettingRow(label, onClick = { onStartupBehaviorChange(behavior) }) {
                EinkRadioMark(
                  selected = startupBehavior == behavior,
                  contentDescription = label,
                )
              }
            }
            BuildInfoRow("Build version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            BuildInfoRow(
              "Build time",
              DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                .format(Date(BuildConfig.BUILD_TIME_EPOCH_MILLIS)),
            )
            UpdateSettingsSection(
              currentVersion = BuildConfig.VERSION_NAME,
              state = updateUiState,
              onCheck = onCheckUpdate,
              onOpenRelease = onOpenRelease,
              pairedClients = pairedClients,
              onlineClients = onlineClients,
              noteLinkState = noteAppUpdateState,
              onUpdateViaNoteLink = onUpdateViaNoteLink,
              onContinueInstall = onContinueNoteAppInstall,
            )
            SettingRow(noteText("备份与恢复", "Backup and restore"), onClick = actions.onOpenBackup) {
              Text(
                noteText("导出 / 恢复 / 回滚", "Export / Restore / Roll back"),
                color = EinkColors.Muted,
                style = EinkType.Caption,
              )
            }
            SettingRow(noteText("开源许可", "Open-source licenses"), onClick = { showOpenSourceLicenses = true }) {
              Text("AGPL-3.0-or-later", color = EinkColors.Muted, style = EinkType.Caption)
            }
          }

          SettingsTab.TOOLBAR -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
          ) {
            Text(
              noteText("工具栏显示", "Toolbar items"),
              style = EinkType.SectionTitle,
              modifier = Modifier.padding(bottom = EinkDimens.SpaceMd),
            )
            ToolbarCatalog.tools
              .filterNot { it.id == ToolbarItem.MENU }
              .chunked(2)
              .forEach { tools ->
                Row(
                  Modifier.fillMaxWidth().padding(bottom = 8.dp),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  tools.forEach { tool ->
                    val visible = tool.id in visibleToolbarItems
                    GridSettingCell(
                      tool.label.localized(NoteI18n.isChinese),
                      Modifier.weight(1f),
                      { onToolbarItemVisibilityChange(tool.id, !visible) },
                    ) {
                      EinkToggle(
                        checked = visible,
                        contentDescription = tool.label.localized(NoteI18n.isChinese),
                      )
                    }
                  }
                  if (tools.size == 1) Spacer(Modifier.weight(1f))
                }
              }
            Text(
              noteText(
                "拖动把手和 Menu 始终显示；关闭的工具会自动进入 Menu。",
                "The drag handle and Menu are always visible. Hidden tools move into Menu.",
              ),
              color = EinkColors.Muted,
              style = EinkType.Caption,
              modifier = Modifier.padding(top = EinkDimens.SpaceXs),
            )
            OutlinedButton(
              onClick = onResetToolbarItems,
              modifier = Modifier.padding(top = EinkDimens.SpaceMd),
              shape = RectangleShape,
            ) {
              Text(noteText("恢复默认显示", "Restore defaults"))
            }
          }

          SettingsTab.SHORTCUTS -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              ShortcutScene.entries.forEach { scene ->
                EinkChoiceCell(
                  modifier = Modifier.heightIn(min = EinkDimens.Touch),
                  selected = scene == shortcutScene,
                  contentDescription = scene.localizedLabel(),
                  onClick = { shortcutScene = scene },
                ) {
                  Text(
                    (if (scene == shortcutScene) "✓ " else "") + scene.localizedLabel(),
                    style = EinkType.Body,
                    modifier = Modifier.padding(horizontal = EinkDimens.SpaceMd),
                  )
                }
              }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(onClick = { onStartShortcutCapture(shortcutScene) }) { Text("按实体键识别") }
              TextButton(onClick = { onShortcutSceneClear(shortcutScene) }) { Text("清空本场景") }
            }
            HardwareBindingsPanel(shortcutScene, shortcutKey ?: HardwareKeyId.K1, shortcutBindings,
              onKey = { shortcutKey = it },
              onBind = { onShortcutBind(shortcutScene, shortcutKey ?: HardwareKeyId.K1, it) })
          }

          SettingsTab.NOTELINK -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
          ) {
            Text(
              noteText("NoteLink 客户端", "NoteLink clients"),
              style = EinkType.SectionTitle,
              modifier = Modifier.padding(bottom = EinkDimens.SpaceSm),
            )
            Text(
              transferStatus,
              style = EinkType.Body,
              modifier = Modifier.padding(bottom = EinkDimens.SpaceSm),
            )
            SettingRow(noteText("显示最近传输事件", "Show recent transfer events"), onClick = {
              onShowRecentTransferEventsChange(!showRecentTransferEvents)
            }) {
              EinkToggle(
                checked = showRecentTransferEvents,
                contentDescription = noteText("显示最近传输事件", "Show recent transfer events"),
              )
            }
            TransferDiagnostics(
              transferSnapshot,
              transferEvents,
              transferEndpointName,
              showRecentTransferEvents,
              onCancelTransfer,
            )
            if (pairedClients.isEmpty()) Text(noteText("尚未配对", "Not paired"))
            pairedClients.forEach { client ->
              val online = onlineClients.firstOrNull { it.client.id == client.id }
              Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Column(Modifier.weight(1f)) {
                  Text(online?.label ?: client.name, style = EinkType.Body)
                  Text(
                    if (client.legacy) {
                      noteText(
                        "旧配对，连接后自动识别",
                        "Legacy pairing; identified on connection",
                      )
                    } else {
                      client.id
                    },
                    style = EinkType.Caption,
                    color = EinkColors.Muted,
                  )
                  Text(
                    (if (online == null) "! " else "✓ ") + if (online == null) {
                      noteText("离线", "Offline")
                    } else {
                      noteText(
                        "在线 · ${online.imageCount} 张图片 · ${online.textCount} 段文字 · ${online.pdfCount} 份 PDF",
                        "Online · ${online.imageCount} images · ${online.textCount} texts · ${online.pdfCount} PDFs",
                      )
                    },
                    style = EinkType.Caption,
                    color = EinkColors.Ink,
                  )
                  Text(
                    noteText(
                      "最近使用",
                      "Last used",
                    ) + " ${DateFormat.getDateTimeInstance(
                      DateFormat.SHORT,
                      DateFormat.SHORT,
                    ).format(Date(client.lastUsedAt))}",
                    style = EinkType.Caption,
                    color = EinkColors.Muted,
                  )
                }
                OutlinedButton(onClick = { renameTarget = client }, shape = RectangleShape) {
                  Text(noteText("重命名", "Rename"))
                }
                OutlinedButton(onClick = { onUnpairClient(client.id) }, shape = RectangleShape) {
                  Text(noteText("移除", "Remove"))
                }
              }
              HorizontalDivider(
                color = EinkColors.ChromeAlt,
                thickness = EinkDimens.Hairline,
              )
            }
            Row(
              Modifier.fillMaxWidth().padding(top = EinkDimens.SpaceMd),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (pairingCode == null) {
                Button(onClick = onStartPairing, shape = RectangleShape) {
                  Text(noteText("配对新设备", "Pair new device"))
                }
              } else {
                OutlinedButton(onClick = onCancelPairing, shape = RectangleShape) {
                  Text(noteText("取消配对", "Cancel pairing"))
                }
              }
              if (!transferPermissionsGranted) {
                OutlinedButton(
                  onClick = onTransferPermissions,
                  shape = RectangleShape,
                ) { Text(noteText("授予权限", "Grant permission")) }
              }
              OutlinedButton(onClick = onRefreshTransferStatus, shape = RectangleShape) {
                Text(noteText("刷新状态", "Refresh status"))
              }
            }
            pairingCode?.let { code ->
              Column(Modifier.fillMaxWidth().padding(top = EinkDimens.SpaceMd)) {
                Text(
                  noteText(
                    "在手机或电脑的 NoteLink 中选择“新增设备”，输入下面的配对码：",
                    "In NoteLink on the phone or PC choose Add device and enter this code:",
                  ),
                  style = EinkType.Body,
                )
                Text(
                  code.chunked(3).joinToString(" "),
                  style = EinkType.ScreenTitle.copy(fontSize = 44.sp, letterSpacing = 6.sp),
                  modifier = Modifier.padding(vertical = EinkDimens.SpaceSm),
                )
                Text(
                  if (pairingInProgress) {
                    noteText("正在寻找输入了配对码的 NoteLink…", "Looking for the NoteLink with this code…")
                  } else {
                    noteText("等待中", "Waiting")
                  },
                  style = EinkType.Caption,
                  color = EinkColors.Muted,
                )
              }
            }
            pairingMessage?.let {
              Text(it, style = EinkType.Caption, color = EinkColors.Ink, modifier = Modifier.padding(top = EinkDimens.SpaceSm))
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
      shape = RectangleShape,
      containerColor = EinkColors.Paper,
      titleContentColor = EinkColors.Ink,
      textContentColor = EinkColors.Ink,
      tonalElevation = 0.dp,
      title = { Text(noteText("重命名 NoteLink", "Rename NoteLink"), style = EinkType.SectionTitle) },
      text = {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          singleLine = true,
          shape = RectangleShape,
          label = { Text(noteText("名称", "Name")) },
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onRenameClient(target.id, name)
            renameTarget = null
          },
          enabled = name.isNotBlank(),
          shape = RectangleShape,
        ) { Text(noteText("保存", "Save")) }
      },
      dismissButton = {
        OutlinedButton(onClick = { renameTarget = null }, shape = RectangleShape) {
          Text(noteText("取消", "Cancel"))
        }
      },
    )
  }

  if (showOpenSourceLicenses) {
    AlertDialog(
      onDismissRequest = { showOpenSourceLicenses = false },
      shape = RectangleShape,
      containerColor = EinkColors.Paper,
      titleContentColor = EinkColors.Ink,
      textContentColor = EinkColors.Ink,
      tonalElevation = 0.dp,
      title = { Text(noteText("开源许可", "Open-source licenses"), style = EinkType.SectionTitle) },
      text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
          Text("BetterHvNote · AGPL-3.0-or-later", style = EinkType.SectionTitle)
          Text(
            noteText(
              "PDF Ink annotation 导出使用 MuPDF fitz 1.28.0，Copyright Artifex Software, Inc.，按 GNU AGPL v3 或更高版本授权。",
              "PDF ink annotation export uses MuPDF fitz 1.28.0, Copyright Artifex Software, Inc., licensed under GNU AGPL v3 or later.",
            ),
            style = EinkType.Body,
            modifier = Modifier.padding(top = EinkDimens.SpaceMd),
          )
          Text(
            noteText("对应源代码：", "Source code:") +
              "\nhttps://github.com/zentialEdwardSu/betterhvnote\n\n" +
              noteText("完整许可：", "Full license:") +
              "\nhttps://www.gnu.org/licenses/agpl-3.0.txt",
            color = EinkColors.Muted,
            style = EinkType.Caption,
            modifier = Modifier.padding(top = EinkDimens.SpaceMd),
          )
          Text(
            noteText(
              "汉王 ROM 接口由设备平台提供，声明仅用于编译，不随应用分发。",
              "Hanvon ROM interfaces are provided by the device platform. Declarations are compile-only and are not distributed with the app.",
            ),
            style = EinkType.Body,
            modifier = Modifier.padding(top = EinkDimens.SpaceMd),
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
      },
    )
  }
}

@Composable
private fun UpdateSettingsSection(
  currentVersion: String,
  state: UpdateUiState,
  onCheck: () -> Unit,
  onOpenRelease: (String) -> Unit,
  pairedClients: List<PairedDevice>,
  onlineClients: List<PhoneTransferClient.AvailableNoteLink>,
  noteLinkState: NoteAppUpdateUiState,
  onUpdateViaNoteLink: (String) -> Unit,
  onContinueInstall: () -> Unit,
) {
  val checkState = state.checkState
  var chooseClient by remember { mutableStateOf(false) }
  val online = onlineClients.map { it.client }
  Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    Text(noteText("应用更新", "App updates"), style = EinkType.SectionTitle)
    Text(
      noteText("当前版本 $currentVersion", "Current version $currentVersion"),
      color = EinkColors.Muted,
      style = EinkType.Caption,
      modifier = Modifier.padding(top = EinkDimens.SpaceXs),
    )
    Text(
      when (checkState) {
        UpdateCheckState.Idle -> noteText("尚未检查", "Not checked yet")

        UpdateCheckState.Checking -> noteText("正在检查 GitHub Releases…", "Checking GitHub Releases...")

        is UpdateCheckState.UpToDate -> checkState.latestVersion?.let {
          noteText("已是最新版本（${it.display}）", "Up to date (${it.display})")
        } ?: noteText("暂无可用正式版本", "No stable release available")

        is UpdateCheckState.UpdateAvailable ->
          noteText(
            "发现新版本 ${checkState.info.latestVersion.display}：${checkState.info.releaseTitle}",
            "New version ${checkState.info.latestVersion.display}: ${checkState.info.releaseTitle}",
          )

        is UpdateCheckState.Failed -> if (state.manualErrorVisible) {
          "Check failed: ${checkState.message}"
        } else {
          "Automatic check is temporarily unavailable"
        }
      },
      color = EinkColors.Muted,
      style = EinkType.Caption,
      modifier = Modifier.padding(top = EinkDimens.SpaceXs),
    )
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = onCheck,
        enabled = checkState !is UpdateCheckState.Checking,
        shape = RectangleShape,
      ) {
        Text(
          if (checkState is UpdateCheckState.Checking) {
            noteText(
              "检查中",
              "Checking",
            )
          } else {
            noteText("检查更新", "Check for updates")
          },
        )
      }
      (checkState as? UpdateCheckState.UpdateAvailable)?.let { available ->
        Button(
          onClick = { onOpenRelease(available.info.releaseUrl) },
          shape = RectangleShape,
        ) { Text(noteText("查看 Release", "View release")) }
      }
    }
    Text(
      when (noteLinkState) {
        NoteAppUpdateUiState.Idle -> noteText(
          "可由已配对的 NoteLink 代为下载并传输",
          "A paired NoteLink can download and transfer the update",
        )
        NoteAppUpdateUiState.Resolving -> noteText("NoteLink 正在查询正式版…", "NoteLink is resolving the stable release...")
        is NoteAppUpdateUiState.Downloading -> noteText(
          "NoteLink 正在下载 ${noteLinkState.version}：${noteLinkState.downloaded}/${noteLinkState.total}",
          "NoteLink is downloading ${noteLinkState.version}: ${noteLinkState.downloaded}/${noteLinkState.total}",
        )
        is NoteAppUpdateUiState.Transferring -> noteText(
          "正在传输 ${noteLinkState.version}…",
          "Transferring ${noteLinkState.version}...",
        )
        is NoteAppUpdateUiState.Ready -> noteText(
          "${noteLinkState.version} 已验证，可继续安装",
          "${noteLinkState.version} is verified and ready to install",
        )
        is NoteAppUpdateUiState.UpToDate -> noteLinkState.latestVersion?.let {
          noteText("NoteLink 确认已是最新版本（$it）", "NoteLink reports this is up to date ($it)")
        } ?: noteText("NoteLink 未找到正式版", "NoteLink found no stable release")
        is NoteAppUpdateUiState.Failed -> noteText(
          "NoteLink 更新失败：${noteLinkState.message}",
          "NoteLink update failed: ${noteLinkState.message}",
        )
      },
      color = EinkColors.Muted,
      style = EinkType.Caption,
      modifier = Modifier.padding(top = EinkDimens.SpaceMd),
    )
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (noteLinkState is NoteAppUpdateUiState.Ready) {
        Button(
          onClick = onContinueInstall,
          shape = RectangleShape,
        ) { Text(noteText("继续安装", "Continue installation")) }
      } else {
        Button(
          enabled = pairedClients.isNotEmpty() && noteLinkState !is NoteAppUpdateUiState.Resolving &&
            noteLinkState !is NoteAppUpdateUiState.Downloading && noteLinkState !is NoteAppUpdateUiState.Transferring,
          onClick = {
            when (online.size) {
              1 -> onUpdateViaNoteLink(online.single().id)
              else -> chooseClient = true
            }
          },
          shape = RectangleShape,
        ) { Text(noteText("通过 NoteLink 更新", "Update via NoteLink")) }
      }
    }
  }
  if (chooseClient) {
    AlertDialog(
      onDismissRequest = { chooseClient = false },
      shape = RectangleShape,
      containerColor = EinkColors.Paper,
      titleContentColor = EinkColors.Ink,
      textContentColor = EinkColors.Ink,
      tonalElevation = 0.dp,
      title = { Text(noteText("选择 NoteLink", "Select NoteLink"), style = EinkType.SectionTitle) },
      text = {
        Column {
          val candidates = online.ifEmpty { pairedClients }
          if (online.isEmpty()) {
            Text(
              noteText(
                "当前未发现在线客户端，将尝试连接已配对设备。",
                "No client is currently online; a paired client will be tried.",
              ),
              style = EinkType.Body,
            )
          }
          candidates.forEach { client ->
            EinkChoiceCell(
              modifier = Modifier.fillMaxWidth().heightIn(min = EinkDimens.Touch),
              selected = false,
              onClick = { chooseClient = false; onUpdateViaNoteLink(client.id) },
            ) {
              Text(client.name, style = EinkType.Body)
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { chooseClient = false }) { Text(noteText("取消", "Cancel")) } },
    )
  }
}

@Composable
private fun TransferDiagnostics(
  snapshot: TransferSnapshot,
  events: List<TransferLogEntry>,
  endpointName: String?,
  showRecentEvents: Boolean,
  onCancel: () -> Unit,
) {
  if (snapshot.phase == TransferPhase.IDLE && (!showRecentEvents || events.isEmpty())) return
  Column(
    Modifier.fillMaxWidth().padding(vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      listOfNotNull(
        "BLE",
        snapshot.phase.name.replace('_', ' '),
        snapshot.mode?.name,
        "SSID ${snapshot.ssidMatch.name}",
      )
        .joinToString(" · "),
    )
    Text(
      "${noteText("本端", "Local")} ${TransferModes.describe(snapshot.localModes)} · " +
        "${noteText("对端", "Remote")} ${TransferModes.describe(snapshot.remoteModes)}" +
        " · ${noteText("尝试", "attempt")} ${snapshot.attempt} · fallback ${snapshot.fallbackCount}",
      style = EinkType.Caption,
      color = EinkColors.Muted,
    )
    snapshot.endpoint?.let {
      Text(
        "${noteText(
          "端点",
          "Endpoint",
        )} ${endpointName ?: snapshot.deviceId ?: noteText("未知设备", "Unknown device")} · ${it.host}:${it.port}",
        style = EinkType.Caption,
        color = EinkColors.Muted,
      )
    }
    Text(
      "Wi-Fi Direct ${noteText(
        "组",
        "group",
      )} ${if (snapshot.wifiDirectGroupReady) noteText("已就绪", "ready") else noteText("未就绪", "not ready")}" +
        listOfNotNull(
          snapshot.deviceId?.let { " · ${noteText("设备", "device")} $it" },
          snapshot.operationId?.let { " · ${noteText("操作", "operation")} ${it.toString().take(8)}" },
        )
          .joinToString(""),
      style = EinkType.Caption,
      color = EinkColors.Muted,
    )
    if (snapshot.totalBytes > 0) {
      EinkProgress(
        progress = (snapshot.bytesTransferred.toFloat() / snapshot.totalBytes).coerceIn(0f, 1f),
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        "${noteFormatBytes(snapshot.bytesTransferred)} / ${noteFormatBytes(snapshot.totalBytes)}" +
          " · ${noteText("当前", "current")} ${noteFormatBytes(snapshot.bytesPerSecond)}/s" +
          " · ${noteText("平均", "average")} ${noteFormatBytes(snapshot.averageBytesPerSecond)}/s" +
          (snapshot.etaMillis?.let { " · ETA ${noteFormatDuration(it)}" } ?: ""),
        style = EinkType.Caption,
        color = EinkColors.Muted,
      )
    }
    snapshot.fallbackReason?.let {
      Text("! fallback ${it.code}: ${it.message}", style = EinkType.Caption, color = EinkColors.Ink)
    }
    snapshot.lastFailure?.let {
      Text(
        "! ${it.code}: ${it.message} · ${if (it.recoverable) {
          noteText(
            "可重试",
            "retryable",
          )
        } else {
          noteText("不可重试", "not retryable")
        }}",
        style = EinkType.Caption,
        color = EinkColors.Ink,
      )
    }
    if (snapshot.canCancel) {
      OutlinedButton(onClick = onCancel, shape = RectangleShape) {
        Text(noteText("取消传输", "Cancel transfer"))
      }
    }
    if (showRecentEvents && events.isNotEmpty()) {
      Text(
        noteText("最近传输事件", "Recent transfer events"),
        style = EinkType.Label,
        modifier = Modifier.padding(top = EinkDimens.SpaceXs),
      )
      Column(
        Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState()),
      ) {
        events.asReversed().forEach { entry ->
          Text(
            "${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(entry.timestampMillis))} " +
              "${entry.category} ${entry.detail}",
            modifier = Modifier.fillMaxWidth().height(20.dp),
            maxLines = 1,
            style = EinkType.Caption,
            color = if (entry.level == TransferLogLevel.ERROR) EinkColors.Ink else EinkColors.Muted,
          )
        }
      }

      HorizontalDivider(
        Modifier.padding(top = EinkDimens.SpaceXl, bottom = EinkDimens.SpaceSm),
        color = EinkColors.ChromeAlt,
        thickness = EinkDimens.Hairline,
      )
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
private fun SettingRow(label: String, onClick: () -> Unit, control: @Composable () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = EinkDimens.Row)
      .clickable(onClick = onClick)
      .padding(vertical = EinkDimens.SpaceSm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(EinkDimens.SpaceMd),
  ) {
    Text(label, modifier = Modifier.weight(1f), style = EinkType.Body)
    control()
  }
}

@Composable
private fun GridSettingCell(label: String, modifier: Modifier, onClick: () -> Unit, control: @Composable () -> Unit) {
  Row(
    modifier = modifier
      .heightIn(min = 64.dp)
      .border(EinkDimens.Hairline, EinkColors.Ink)
      .clickable(onClick = onClick)
      .padding(horizontal = EinkDimens.SpaceMd, vertical = EinkDimens.SpaceSm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(EinkDimens.SpaceSm),
  ) {
    Text(label, modifier = Modifier.weight(1f), style = EinkType.Body)
    control()
  }
}

@Composable
private fun BuildInfoRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = EinkDimens.SpaceSm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(EinkDimens.SpaceMd),
  ) {
    Text(label, modifier = Modifier.weight(1f), style = EinkType.Label)
    Text(value, style = EinkType.Caption, color = EinkColors.Muted)
  }
}

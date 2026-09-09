package com.betterhv.note

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.PageKind
import com.betterhv.note.doc.TextFontFamily
import com.betterhv.note.doc.TextObject
import com.betterhv.note.export.ExportViewModel
import com.betterhv.note.storage.StartupBehavior
import com.betterhv.note.template.TemplateStore
import com.betterhv.transfer.android.TransferPermissions
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.RemotePayload
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferSnapshot
import com.betterhv.transfer.core.isActiveTransferPhase
import com.betterhv.update.UpdateCheckState
import com.betterhv.update.UpdateChecker
import com.betterhv.update.UpdateProduct
import com.betterhv.update.UpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
  private var penView: PenDrawView? = null
  private var releaseTransientInputGuards: (() -> Unit)? = null
  private var hardwareKeyHandler: ((HardwareKeyId) -> Unit)? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    EventLog.log("MainActivity", "onCreate")
    NativeSelfTest.run()
    setContent {
      AppRoot(
        onView = { penView = it },
        onTransientInputGuardReleaseReady = { releaseTransientInputGuards = it },
        onHardwareKeyHandlerReady = { hardwareKeyHandler = it },
        skipTemplateDirectoryPrompt = intent.getBooleanExtra(
          EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT,
          false,
        ),
      )
    }
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    PenButtonTracker.observeMotion(event, "touch")
    val handled = super.dispatchTouchEvent(event)
    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
      releaseTransientInputGuards?.invoke()
    }
    return handled
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    PenButtonTracker.observeMotion(event, "generic")
    val handled = super.dispatchGenericMotionEvent(event)
    if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT || event.actionMasked == MotionEvent.ACTION_CANCEL) {
      releaseTransientInputGuards?.invoke()
    }
    return handled
  }

  @SuppressLint("RestrictedApi")
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    PenButtonTracker.observeKey(event)
    if (N10ProHardwareKeys.dispatch(event) { hardwareKeyHandler?.invoke(it) }) return true
    return super.dispatchKeyEvent(event)
  }

  companion object {
    /** Test-only escape hatch so UI automation is not covered by the system folder picker. */
    const val EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT =
      "com.betterhv.note.extra.SKIP_TEMPLATE_DIRECTORY_PROMPT"
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) N10ProHardwareKeys.enterNoteKeyScene(this)
  }

  override fun onDestroy() {
    releaseTransientInputGuards = null
    hardwareKeyHandler = null
    penView?.teardown()
    super.onDestroy()
  }

  internal fun currentPenViewForTest(): PenDrawView? = penView
}

private sealed interface NotebookCreationRequest {
  data class Transfer(val pageIds: Set<UUID>) : NotebookCreationRequest
  data object Blank : NotebookCreationRequest
}

private sealed interface TextEditorRequest {
  val initialText: String
  data class New(val x: Float, val y: Float, override val initialText: String = "") : TextEditorRequest
  data class Existing(val objectId: UUID, override val initialText: String) : TextEditorRequest
}

private data class PendingLinkedNoteRequest(val selection: PdfRegionSelection)

private fun contentKindLabel(kind: ContentKind): String = when (kind) {
  ContentKind.IMAGE -> noteText("图片", "image")
  ContentKind.TEXT -> noteText("文字", "text")
  ContentKind.PDF -> "PDF"
  ContentKind.APP_PACKAGE -> noteText("应用安装包", "app package")
}

internal enum class InsertionStartDecision { CHOOSE_SOURCE, DISCOVER_REMOTE, REQUEST_PERMISSIONS }

internal fun insertionStartDecision(
  directWhenAvailable: Boolean,
  permissionsAvailable: Boolean,
): InsertionStartDecision = when {
  !directWhenAvailable -> InsertionStartDecision.CHOOSE_SOURCE
  permissionsAvailable -> InsertionStartDecision.DISCOVER_REMOTE
  else -> InsertionStartDecision.REQUEST_PERMISSIONS
}

@Composable
private fun PdfRegionActionPopup(selection: PdfRegionSelection, onEdit: () -> Unit, onMoveToInbox: () -> Unit) {
  val density = LocalDensity.current
  val windowSize = LocalWindowInfo.current.containerSize
  val popupWidth = with(density) { 104.dp.toPx() }
  val popupHeight = with(density) { 52.dp.toPx() }
  val margin = with(density) { 8.dp.toPx() }
  val screenWidth = windowSize.width.toFloat()
  val screenHeight = windowSize.height.toFloat()
  val x = ((selection.screenBounds.left + selection.screenBounds.right - popupWidth) / 2f)
    .coerceIn(margin, (screenWidth - popupWidth - margin).coerceAtLeast(margin))
  val preferredTop = selection.screenBounds.top - popupHeight - margin
  val y = (if (preferredTop >= margin) preferredTop else selection.screenBounds.bottom + margin)
    .coerceIn(margin, (screenHeight - popupHeight - margin).coerceAtLeast(margin))
  Surface(
    modifier = Modifier.offset { IntOffset(x.toInt(), y.toInt()) }
      .border(1.dp, Color.Black, RectangleShape),
    shape = RectangleShape,
    color = Color.White,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      IconButton(
        onClick = onEdit,
        enabled = selection.selectedObjectIds.isNotEmpty(),
        modifier = Modifier.size(48.dp),
      ) {
        Icon(Icons.Filled.Edit, contentDescription = noteText("编辑选区批注", "Edit selection annotations"))
      }
      IconButton(onClick = onMoveToInbox, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.MoveToInbox, contentDescription = noteText("发送至夹纸", "Send to linked note"))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
  onView: (PenDrawView) -> Unit,
  onTransientInputGuardReleaseReady: ((() -> Unit)?) -> Unit,
  onHardwareKeyHandlerReady: (((HardwareKeyId) -> Unit)?) -> Unit,
  skipTemplateDirectoryPrompt: Boolean,
) {
  val context = LocalContext.current
  val exportViewModel: ExportViewModel = viewModel()
  val density = LocalDensity.current
  val windowSize = LocalWindowInfo.current.containerSize
  val thumbnailSize = DpSize(
    width = with(density) { windowSize.width.toDp() } / THUMBNAIL_SCREEN_SCALE,
    height = with(density) { windowSize.height.toDp() } / THUMBNAIL_SCREEN_SCALE,
  )
  val notebookThumbnailSize = DpSize(
    width = with(density) { windowSize.width.toDp() } / NOTEBOOK_THUMBNAIL_SCREEN_SCALE,
    height = with(density) { windowSize.height.toDp() } / NOTEBOOK_THUMBNAIL_SCREEN_SCALE,
  )
  val penSettingsStore = remember(context) { PenSettingsStore(context) }
  val appSettingsStore = remember(context) { AppSettingsStore(context) }
  var language by remember { mutableStateOf(appSettingsStore.language) }
  val notificationState = rememberInAppNotificationState()
  val updateNotificationState = rememberInAppNotificationState()
  val appScope = rememberCoroutineScope()
  val phoneTransfer = remember(context) { PhoneTransferClient(context) }
  val noteAppInstaller = remember(context) { NoteAppInstaller(context) }
  var noteAppUpdateState by remember {
    mutableStateOf<NoteAppUpdateUiState>(
      noteAppInstaller.pending()?.let { NoteAppUpdateUiState.Ready(it.versionName) }
        ?: NoteAppUpdateUiState.Idle,
    )
  }
  val transferSnapshot by phoneTransfer.snapshot.collectAsState()
  val transferEvents by phoneTransfer.eventHistory.collectAsState()
  val insertion = remember(phoneTransfer) { InsertionCoordinator(phoneTransfer) }
  val insertionState by insertion.state.collectAsState()
  DisposableEffect(insertion) { onDispose { insertion.close() } }
  val showNotice: (String) -> Unit = remember(notificationState, appScope) {
    { message -> appScope.launch { notificationState.show(title = message) } }
  }
  val updateChecker = remember { UpdateChecker() }
  var updateUiState by remember { mutableStateOf(UpdateUiState()) }
  val openRelease: (String) -> Unit = { url ->
    if (!UpdateChecker.isTrustedReleaseUrl(url)) {
      showNotice("Invalid release URL")
    } else {
      runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { showNotice("Cannot open release page: ${it.message}") }
    }
  }
  val checkForUpdates: (Boolean) -> Unit = { manual ->
    if (updateUiState.checkState !is UpdateCheckState.Checking) {
      updateUiState = updateUiState.checking(manual)
      appScope.launch {
        val result = withContext(Dispatchers.IO) {
          updateChecker.check(UpdateProduct.NOTE, BuildConfig.VERSION_NAME)
        }
        updateUiState = updateUiState.completed(result, manual)
      }
    }
  }
  LaunchedEffect(updateChecker) {
    updateUiState = updateUiState.checking(manual = false)
    val result = withContext(Dispatchers.IO) {
      updateChecker.check(UpdateProduct.NOTE, BuildConfig.VERSION_NAME)
    }
    updateUiState = updateUiState.completed(result, manual = false)
  }
  var penToolbarSettings by remember { mutableStateOf(penSettingsStore.load()) }
  val activePenStyle = remember(penToolbarSettings) {
    PenProfiles.style(penToolbarSettings.activeSettings(), Build.MODEL.orEmpty())
  }

  Box(Modifier.fillMaxSize().background(Color.White)) {
    // Full-screen writing canvas (hosts the ROM EPD pen overlay). Held in
    // Compose state (not a bare local var) so the closures captured by
    // EraserOverlayView's factory and the toolbar's callbacks keep seeing
    // the real instance across recomposition -- a bare `var` here gets
    // reset to null on every recomposition triggered by docVersion, which
    // silently turned every toolbar action (tool switch/undo/redo/delete)
    // into a no-op after the first stroke.
    var penView by remember { mutableStateOf<PenDrawView?>(null) }

    // Recomposition trigger: PenDrawView calls this after every document
    // mutation (draw/erase/split/move/scale/delete/undo/redo) via
    // setOnDocChanged, since none of that state is Compose State on its own.
    var docVersion by remember { mutableIntStateOf(0) }
    var toolKind by remember { mutableStateOf(ToolKind.PEN) }
    var eraserMode by remember { mutableStateOf(EraserMode.WHOLE_STROKE) }
    var debugMode by remember { mutableStateOf(false) }
    var dockEdge by rememberSaveable { mutableStateOf(appSettingsStore.toolbarDockEdge) }
    var dockFraction by rememberSaveable { mutableFloatStateOf(appSettingsStore.toolbarDockFraction) }
    var toolbarHidden by rememberSaveable { mutableStateOf(appSettingsStore.toolbarHidden) }
    var visibleToolbarItems by remember { mutableStateOf(appSettingsStore.visibleToolbarItems) }
    var shortcutBindings by remember { mutableStateOf(appSettingsStore.loadHardwareShortcuts()) }
    var shortcutCaptureScene by remember { mutableStateOf<ShortcutScene?>(null) }
    var shortcutBindingRequest by remember { mutableStateOf<Pair<ShortcutScene, HardwareKeyId>?>(null) }
    var pendingHardwareKey by remember { mutableStateOf<HardwareKeyId?>(null) }
    var toolbarShortcutScene by remember { mutableStateOf<ShortcutScene?>(null) }
    var pageManagerOpen by rememberSaveable { mutableStateOf(false) }
    var penSettingsOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var documentSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var templateChooserOpen by rememberSaveable { mutableStateOf(false) }
    var notebookManagerOpen by rememberSaveable { mutableStateOf(false) }
    var exportPanelOpen by rememberSaveable { mutableStateOf(false) }
    var exportInitialNotebookId by remember { mutableStateOf<UUID?>(null) }
    var exportCreationRequest by rememberSaveable { mutableLongStateOf(0L) }
    var notebookBusy by remember { mutableStateOf(false) }
    var pendingNotebookCreation by remember { mutableStateOf<NotebookCreationRequest?>(null) }
    var textEditorRequest by remember { mutableStateOf<TextEditorRequest?>(null) }
    var pendingPdfRegion by remember { mutableStateOf<PdfRegionSelection?>(null) }
    var pendingLinkedNoteRequest by remember { mutableStateOf<PendingLinkedNoteRequest?>(null) }
    var templateCatalog by remember { mutableStateOf(TemplateStore.get(context).snapshot()) }
    var toolbarInteractionBlocked by remember { mutableStateOf(false) }
    var pageControlInteractionBlocked by remember { mutableStateOf(false) }
    var notificationInteractionBlocked by remember { mutableStateOf(false) }
    var debugInteractionBlocked by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
      onTransientInputGuardReleaseReady {
        toolbarInteractionBlocked = false
        pageControlInteractionBlocked = false
        notificationInteractionBlocked = false
        debugInteractionBlocked = false
      }
      onDispose { onTransientInputGuardReleaseReady(null) }
    }
    DisposableEffect(Unit) {
      onHardwareKeyHandlerReady { pendingHardwareKey = it }
      onDispose { onHardwareKeyHandlerReady(null) }
    }
    var startupBehavior by remember { mutableStateOf(StartupBehavior.WORKING_COPY) }
    var skipSourceSelectionWhenQueueAvailable by remember {
      mutableStateOf(appSettingsStore.skipSourceSelectionWhenQueueAvailable)
    }
    var autoCreatePageOnNextAtEnd by remember {
      mutableStateOf(appSettingsStore.autoCreatePageOnNextAtEnd)
    }
    var showRecentTransferEvents by remember {
      mutableStateOf(appSettingsStore.showRecentTransferEvents)
    }
    var pairedClients by remember { mutableStateOf(phoneTransfer.pairing.pairedClients) }
    var onlineNoteLinks by remember {
      mutableStateOf<List<PhoneTransferClient.AvailableNoteLink>>(emptyList())
    }
    var pairingCandidates by remember {
      mutableStateOf<List<PhoneTransferClient.PairingCandidate>>(emptyList())
    }
    var pairingScanActive by remember { mutableStateOf(false) }
    var pairingInProgress by remember { mutableStateOf(false) }
    var suppressNextOnlineDiscovery by remember { mutableStateOf(false) }
    var transferStatusRevision by remember { mutableIntStateOf(0) }
    val updateViaNoteLink: (String) -> Unit = { clientId ->
      if (!TransferPermissions.hasAllNotePermissions(context)) {
        noteAppUpdateState = NoteAppUpdateUiState.Failed(
          noteText("请先授予附近设备权限", "Grant Nearby devices permission first"),
        )
      } else {
        appScope.launch {
          runCatching {
            phoneTransfer.requestNoteAppUpdate(clientId, BuildConfig.VERSION_NAME) { progress ->
              appScope.launch {
                noteAppUpdateState = when (progress) {
                  PhoneTransferClient.NoteAppUpdateProgress.Resolving -> NoteAppUpdateUiState.Resolving
                  is PhoneTransferClient.NoteAppUpdateProgress.Downloading -> NoteAppUpdateUiState.Downloading(
                    progress.version, progress.bytesDownloaded, progress.totalBytes,
                  )
                  is PhoneTransferClient.NoteAppUpdateProgress.Transferring ->
                    NoteAppUpdateUiState.Transferring(progress.version)
                }
              }
            }
          }.onSuccess { outcome ->
            when (outcome) {
              is PhoneTransferClient.NoteAppUpdateOutcome.UpToDate -> {
                noteAppUpdateState = NoteAppUpdateUiState.UpToDate(outcome.latestVersion)
              }
              is PhoneTransferClient.NoteAppUpdateOutcome.Package -> {
                val payload = outcome.lease.payload as? RemotePayload.AppPackage
                if (payload == null) {
                  runCatching { outcome.lease.commitAndAwait() }
                  noteAppUpdateState = NoteAppUpdateUiState.Failed("NoteLink returned a non-APK payload")
                } else {
                  val staged = runCatching {
                    withContext(Dispatchers.IO) {
                      noteAppInstaller.validateAndStage(payload.stagedFile, outcome.version)
                    }
                  }
                  runCatching { outcome.lease.commitAndAwait() }
                  staged.onSuccess { pending ->
                    noteAppUpdateState = NoteAppUpdateUiState.Ready(pending.versionName)
                    if (!noteAppInstaller.launch(pending)) {
                      showNotice(
                        noteText(
                          "请允许此来源安装应用，然后点击“继续安装”",
                          "Allow installs from this source, then tap Continue installation",
                        ),
                      )
                    }
                  }.onFailure { error ->
                    payload.stagedFile.delete()
                    noteAppUpdateState = NoteAppUpdateUiState.Failed(error.message ?: "APK validation failed")
                  }
                }
              }
            }
          }.onFailure { error ->
            noteAppUpdateState = NoteAppUpdateUiState.Failed(error.message ?: "NoteLink update failed")
          }
        }
      }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
      var stopped = false
      val observer = LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_STOP -> stopped = true

          Lifecycle.Event.ON_RESUME -> {
            noteAppInstaller.pending()?.let { noteAppUpdateState = NoteAppUpdateUiState.Ready(it.versionName) }
            if (stopped) {
              stopped = false
              appScope.launch {
                phoneTransfer.recoverAfterWake()
                penView?.recoverAfterWake()
                templateCatalog = penView?.refreshTemplates() ?: templateCatalog
                transferStatusRevision++
              }
            } else {
              penView?.recoverAfterWake()
              templateCatalog = penView?.refreshTemplates() ?: templateCatalog
              transferStatusRevision++
            }
          }

          else -> Unit
        }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val missingTransferPermissions = remember(transferStatusRevision) {
      TransferPermissions.missingNotePermissions(context)
    }
    val transferStatus = remember(transferStatusRevision, pairedClients, language) {
      noteTransferStatus(context, pairedClients.isNotEmpty(), missingTransferPermissions.isEmpty())
    }
    LaunchedEffect(transferStatusRevision, pairedClients) {
      if (suppressNextOnlineDiscovery) {
        suppressNextOnlineDiscovery = false
        onlineNoteLinks = emptyList()
        return@LaunchedEffect
      }
      onlineNoteLinks = if (missingTransferPermissions.isEmpty() && pairedClients.isNotEmpty()) {
        runCatching {
          phoneTransfer.discoverAvailable(timeoutMillis = 3_000L) { onlineNoteLinks = it }
        }.getOrDefault(emptyList())
      } else {
        emptyList()
      }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      appScope.launch {
        insertion.localImage(uri)
        if (insertion.state.value is InsertionState.ImageReady) {
          showNotice(
            noteText("图片已导入，请点击页面确定位置", "Image imported. Tap the page to place it."),
          )
        }
      }
    }
    var permissionRemoteKind by remember { mutableStateOf<ContentKind?>(null) }
    var permissionRemoteAuto by remember { mutableStateOf(false) }
    var exportPermissionResult by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
    val transferPermissionLauncher = rememberLauncherForActivityResult(
      ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
      transferStatusRevision++
      exportPermissionResult?.complete(TransferPermissions.hasAllNotePermissions(context))
      exportPermissionResult = null
      val kind = permissionRemoteKind.also { permissionRemoteKind = null }
      val auto = permissionRemoteAuto.also { permissionRemoteAuto = false }
      if (kind != null && TransferPermissions.hasAllNotePermissions(context)) {
        appScope.launch {
          if (auto) insertion.remoteIfAvailableOrChoose(kind) else insertion.remote(kind)
        }
      } else if (kind != null) {
        showNotice(
          "Nearby devices permission is required to receive from a phone",
        )
      }
    }

    val beginInsertion: (ContentKind) -> Unit = { kind ->
      val missing = TransferPermissions.missingNotePermissions(context)
      when (insertionStartDecision(skipSourceSelectionWhenQueueAvailable, missing.isEmpty())) {
        InsertionStartDecision.CHOOSE_SOURCE -> insertion.choose(kind)
        InsertionStartDecision.DISCOVER_REMOTE -> {
          appScope.launch { insertion.remoteIfAvailableOrChoose(kind) }
        }
        InsertionStartDecision.REQUEST_PERMISSIONS -> {
          permissionRemoteKind = kind
          permissionRemoteAuto = true
          transferPermissionLauncher.launch(missing)
        }
      }
    }

    AndroidView(
      factory = { ctx ->
        PenDrawView(ctx).also {
          it.setPenStyle(activePenStyle)
          penView = it
          onView(it)
          it.setOnDocChanged { docVersion++ }
          it.setOnNotice(showNotice)
          it.setOnPdfRegionSelected { bounds -> pendingPdfRegion = bounds }
          startupBehavior = it.startupBehavior()
          it.autoCreatePageOnNextAtEnd = autoCreatePageOnNextAtEnd
          insertion.attach(it)
        }
      },
      modifier = Modifier.fillMaxSize(),
    )
    LaunchedEffect(penView, activePenStyle) {
      penView?.setPenStyle(activePenStyle)
    }
    // Eraser input layer: a non-hvpen view stacked above the pen canvas so it
    // receives the full touch stream the ROM withholds from the hvpen-
    // registered PenDrawView. It only acts on the eraser tool; pen events
    // fall through to PenDrawView. See EraserOverlayView.
    penView?.let { pv ->
      val linkedNotePlacementOpen = docVersion.let { pv.hasPendingLinkedNotePlacement() }
      AndroidView(
        factory = { ctx -> EraserOverlayView(ctx, pv) },
        modifier = Modifier.fillMaxSize(),
      )
      AndroidView(
        factory = { ctx -> PageControlOverlayView(ctx, pv, showNotice) },
        modifier = Modifier.fillMaxSize(),
      )
      AndroidView(
        factory = {
          ObjectEditOverlayView(it, pv).also { overlay ->
            overlay.onPlacementTap = { x, y ->
              if (pv.hasPendingLinkedNotePlacement()) {
                notebookBusy = true
                pv.placePendingLinkedNote(x, y) { result ->
                  notebookBusy = false
                  result.onSuccess {
                    showNotice(
                      noteText(
                        "已建立双向链接：点击截图或 PDF 框角落的 Link 图标即可跳转；Side1 仍用于编辑图片",
                        "Two-way link created. Tap the Link icon on the image or PDF selection to jump; Side1 still edits the image.",
                      ),
                    )
                  }
                }
              } else {
                when (val request = insertion.state.value) {
                  is InsertionState.ImageReady -> insertion.placeImage(x, y)
                    .onSuccess {
                      showNotice(
                        noteText("图片已插入；使用 Side1 点击可编辑", "Image inserted. Use Side1 and tap it to edit."),
                      )
                    }
                    .onFailure { showNotice("Image insertion failed: ${it.message}") }

                  is InsertionState.TextReady ->
                    textEditorRequest = TextEditorRequest.New(x, y, request.initialText)

                  else -> Unit
                }
              }
            }
          }
        },
        update = { overlay ->
          overlay.onPlacementTap = if (
            linkedNotePlacementOpen || insertionState is InsertionState.ImageReady ||
            insertionState is InsertionState.TextReady
          ) {
            { x, y ->
              if (pv.hasPendingLinkedNotePlacement()) {
                notebookBusy = true
                pv.placePendingLinkedNote(x, y) { result ->
                  notebookBusy = false
                  result.onSuccess {
                    showNotice(
                      noteText(
                        "已建立双向链接：点击截图或 PDF 框角落的 Link 图标即可跳转；Side1 仍用于编辑图片",
                        "Two-way link created. Tap the Link icon on the image or PDF selection to jump; Side1 still edits the image.",
                      ),
                    )
                  }
                }
              } else {
                when (val request = insertion.state.value) {
                  is InsertionState.ImageReady -> insertion.placeImage(x, y)
                    .onSuccess {
                      showNotice(
                        noteText("图片已插入；使用 Side1 点击可编辑", "Image inserted. Use Side1 and tap it to edit."),
                      )
                    }
                    .onFailure { showNotice("Image insertion failed: ${it.message}") }

                  is InsertionState.TextReady ->
                    textEditorRequest = TextEditorRequest.New(x, y, request.initialText)

                  else -> Unit
                }
              }
            }
          } else {
            null
          }
          pv.setOnTextEditRequested { text ->
            textEditorRequest = TextEditorRequest.Existing(text.id, text.text)
          }
          pv.setOnPdfRegionSelected { bounds -> pendingPdfRegion = bounds }
        },
        modifier = Modifier.fillMaxSize(),
      )
      // Navigation/lasso must be the topmost transparent input layer.
      // Otherwise a modifier gesture can be captured by the page-turn or
      // object-edit layer before this view sees its ACTION_DOWN.
      AndroidView(
        factory = { ctx -> LassoOverlayView(ctx, pv) },
        modifier = Modifier.fillMaxSize(),
      )
    }
    val templateDirectoryPicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      appScope.launch {
        runCatching {
          withContext(Dispatchers.IO) { TemplateStore.get(context).connect(uri) }
        }.onSuccess {
          templateCatalog = penView?.refreshTemplates(force = true) ?: it
          exportViewModel.refresh()
          showNotice(noteText("模板目录已连接", "Template folder connected"))
        }.onFailure {
          showNotice(
            "Could not connect template folder: ${it.message}",
          )
        }
      }
    }
    LaunchedEffect(Unit) {
      val store = TemplateStore.get(context)
      if (!skipTemplateDirectoryPrompt && !store.hasUsableDirectoryPermission()) {
        val previous = appSettingsStore.templateDirectoryUri
          ?.let { runCatching { it.toUri() }.getOrNull() }
        templateDirectoryPicker.launch(previous)
      }
    }

    val uiInputBlockState = UiInputBlockState(
      pageManagerOpen = pageManagerOpen,
      toolbarPopupOpen = penSettingsOpen,
      settingsOpen = settingsOpen || documentSettingsOpen || templateChooserOpen,
      notebookManagerOpen = notebookManagerOpen,
      notebookNameOpen = pendingNotebookCreation != null,
      insertionOpen = insertionState !is InsertionState.Idle,
      textEditorOpen = textEditorRequest != null,
      notebookBusy = notebookBusy,
      exportPanelOpen = exportPanelOpen,
      richObjectSelected = penView?.selectedRichObject() != null,
      toolbarInteraction = toolbarInteractionBlocked,
      pageControlInteraction = pageControlInteractionBlocked,
      notificationInteraction = notificationInteractionBlocked,
      debugInteraction = debugInteractionBlocked,
      pdfRegionDialogOpen = pendingPdfRegion != null || pendingLinkedNoteRequest != null,
      linkedNotePlacementOpen = penView?.hasPendingLinkedNotePlacement() == true,
    )
    LaunchedEffect(penView, uiInputBlockState, docVersion) {
      penView?.setUiInputBlocked(uiInputBlockState.blocked)
    }

    BackHandler(
      enabled = !notebookBusy && (
        textEditorRequest != null || insertionState !is InsertionState.Idle || pendingNotebookCreation != null ||
          pendingPdfRegion != null || pendingLinkedNoteRequest != null ||
          penView?.hasPendingLinkedNotePlacement() == true || settingsOpen || documentSettingsOpen ||
          templateChooserOpen || notebookManagerOpen || pageManagerOpen || exportPanelOpen ||
          penView?.canNavigateDocumentBack() == true
        ),
    ) {
      when {
        templateChooserOpen -> templateChooserOpen = false

        documentSettingsOpen -> documentSettingsOpen = false

        penView?.hasPendingLinkedNotePlacement() == true -> {
          penView?.cancelPendingLinkedNotePlacement()
          showNotice(noteText("已取消夹纸内容放置", "Linked-note placement cancelled"))
        }

        pendingLinkedNoteRequest != null -> pendingLinkedNoteRequest = null

        pendingPdfRegion != null -> pendingPdfRegion = null

        textEditorRequest != null -> {
          if (textEditorRequest is TextEditorRequest.New) insertion.cancel()
          textEditorRequest = null
        }

        insertionState !is InsertionState.Idle -> insertion.cancel()

        pendingNotebookCreation != null -> pendingNotebookCreation = null

        settingsOpen -> settingsOpen = false

        notebookManagerOpen -> notebookManagerOpen = false

        pageManagerOpen -> pageManagerOpen = false

        exportPanelOpen -> exportPanelOpen = false

        penView?.canNavigateDocumentBack() == true -> penView?.navigateDocumentBack()
      }
    }

    // Debug-only affordances: the event-log overlay and the Flush/Clear
    // buttons used to exercise materialize()/clear() without a real
    // erase or page-turn gesture. Hidden by default so the toolbar's
    // Menu > Debug mode toggle controls whether they show.
    if (debugMode) {
      LogOverlay(
        modifier = Modifier.align(Alignment.TopStart).padding(8.dp).width(360.dp),
      )
      Row(
        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
          .penInputGuard { debugInteractionBlocked = it },
      ) {
        Button(onClick = { penView?.flushToBitmap() }) { Text("Flush") }
        Button(onClick = { penView?.clear() }, modifier = Modifier.padding(start = 8.dp)) { Text("Clear") }
      }
    }

    // Reading docVersion here (not wrapping the toolbar in key(docVersion))
    // makes AppRoot recompose whenever the document changes, so undo/redo/
    // delete enabled state stays current -- WITHOUT remounting the toolbar.
    // A key() wrapper would destroy and recreate the whole toolbar subtree on
    // every doc mutation, discarding its own rememberSaveable state (dockEdge,
    // menuExpanded) -- that was the "toolbar snaps back / submenu collapses on
    // pen down" bug. canUndo/canRedo/currentSelection are plain calls, not
    // Compose state, so we touch docVersion to establish the read dependency.
    val gen = docVersion
    val canUndo = gen.let { penView?.canUndo() ?: false }
    val canRedo = gen.let { penView?.canRedo() ?: false }
    val hasSelection = gen.let { penView?.currentSelection()?.isEmpty?.not() ?: false }
    var requestedToolbarItem by remember { mutableStateOf<ToolbarItem?>(null) }
    var pendingFlyoutShortcutAction by remember { mutableStateOf<ShortcutAction?>(null) }
    var shortcutDeletePageId by remember { mutableStateOf<UUID?>(null) }
    var shortcutDeleteAt by remember { mutableLongStateOf(0L) }

    val invokeToolbarItem: (ToolbarItem) -> Unit = { item ->
      when (item) {
        ToolbarItem.PEN_1, ToolbarItem.PEN_2, ToolbarItem.PEN_3 -> {
          val slot = item.ordinal
          penToolbarSettings = penToolbarSettings.selectSlot(slot)
          penSettingsStore.save(penToolbarSettings)
          toolKind = ToolKind.PEN
          penView?.setTool(ToolKind.PEN)
        }

        ToolbarItem.TAIL_ERASER -> {
          toolbarHidden = false
          appSettingsStore.toolbarHidden = false
          requestedToolbarItem = item
        }

        ToolbarItem.LASSO -> {
          toolKind = ToolKind.LASSO
          penView?.setTool(ToolKind.LASSO)
        }

        ToolbarItem.NAVIGATION -> {
          toolKind = ToolKind.NAVIGATION
          penView?.setTool(ToolKind.NAVIGATION)
        }

        ToolbarItem.INSERT, ToolbarItem.MENU -> {
          toolbarHidden = false
          appSettingsStore.toolbarHidden = false
          requestedToolbarItem = item
        }

        ToolbarItem.UNDO -> penView?.undo()

        ToolbarItem.REDO -> penView?.redo()

        ToolbarItem.DELETE -> penView?.deleteSelection()

        ToolbarItem.PAGES -> {
          pageManagerOpen = !pageManagerOpen
          notebookManagerOpen = false
          settingsOpen = false
        }

        ToolbarItem.NOTEBOOKS -> {
          notebookManagerOpen = !notebookManagerOpen
          settingsOpen = false
          pageManagerOpen = false
        }

        ToolbarItem.EXPORT -> {
          exportPanelOpen = !exportPanelOpen
          if (exportPanelOpen) {
            exportInitialNotebookId = penView?.currentNotebookId()
            exportViewModel.refresh()
          }
          notebookManagerOpen = false
          settingsOpen = false
          pageManagerOpen = false
        }
      }
    }

    LaunchedEffect(pendingHardwareKey) {
      val key = pendingHardwareKey ?: return@LaunchedEffect
      pendingHardwareKey = null
      shortcutCaptureScene?.let { scene ->
        shortcutBindingRequest = scene to key
        shortcutCaptureScene = null
        return@LaunchedEffect
      }
      if (penView?.isInputGestureActive() == true) return@LaunchedEffect
      val modalWithoutShortcuts = penSettingsOpen || settingsOpen || documentSettingsOpen ||
        templateChooserOpen || notebookManagerOpen || exportPanelOpen ||
        textEditorRequest != null || pendingNotebookCreation != null || notebookBusy
      val scene = when {
        pageManagerOpen -> ShortcutScene.PAGE_MANAGER

        insertionState is InsertionState.ChoosingSource ||
          insertionState is InsertionState.ChoosingClient -> ShortcutScene.INSERT

        toolbarShortcutScene != null -> toolbarShortcutScene

        modalWithoutShortcuts -> null

        else -> ShortcutScene.EDITOR
      }
      val action = scene?.let { shortcutBindings.action(it, key) } ?: return@LaunchedEffect
      when (scene) {
        ShortcutScene.EDITOR -> when (action) {
          ShortcutAction.EDITOR_PEN_1 -> invokeToolbarItem(ToolbarItem.PEN_1)

          ShortcutAction.EDITOR_PEN_2 -> invokeToolbarItem(ToolbarItem.PEN_2)

          ShortcutAction.EDITOR_PEN_3 -> invokeToolbarItem(ToolbarItem.PEN_3)

          ShortcutAction.EDITOR_TAIL_ERASER -> invokeToolbarItem(ToolbarItem.TAIL_ERASER)

          ShortcutAction.EDITOR_LASSO -> invokeToolbarItem(ToolbarItem.LASSO)

          ShortcutAction.EDITOR_INSERT -> invokeToolbarItem(ToolbarItem.INSERT)

          ShortcutAction.EDITOR_UNDO -> invokeToolbarItem(ToolbarItem.UNDO)

          ShortcutAction.EDITOR_REDO -> invokeToolbarItem(ToolbarItem.REDO)

          ShortcutAction.EDITOR_DELETE -> invokeToolbarItem(ToolbarItem.DELETE)

          ShortcutAction.EDITOR_PAGES -> invokeToolbarItem(ToolbarItem.PAGES)

          ShortcutAction.EDITOR_NOTEBOOKS -> invokeToolbarItem(ToolbarItem.NOTEBOOKS)

          ShortcutAction.EDITOR_EXPORT -> invokeToolbarItem(ToolbarItem.EXPORT)

          ShortcutAction.EDITOR_MENU -> invokeToolbarItem(ToolbarItem.MENU)

          ShortcutAction.EDITOR_TOGGLE_TOOLBAR -> {
            toolbarHidden = !toolbarHidden
            appSettingsStore.toolbarHidden = toolbarHidden
          }

          else -> Unit
        }

        ShortcutScene.PAGE_MANAGER -> PageManagerShortcutContext { pageAction ->
          val pv = penView ?: return@PageManagerShortcutContext false
          when (pageAction) {
            ShortcutAction.PAGE_PREVIOUS -> {
              if (!pv.navigatePage(-1)) showNotice(noteText("已经是第一页", "Already at the first page"))
            }

            ShortcutAction.PAGE_NEXT -> {
              if (!pv.navigatePage(1)) showNotice(noteText("已经是最后一页", "Already at the last page"))
            }

            ShortcutAction.PAGE_ADD -> {
              pv.addPageAfter(pv.pageIds()[pv.currentPageIndex()], activate = true)
              showNotice(noteText("已新增第 ${pv.currentPageIndex() + 1} 页", "Added page ${pv.currentPageIndex() + 1}"))
            }

            ShortcutAction.PAGE_DELETE -> {
              val id = pv.pageIds().getOrNull(pv.currentPageIndex()) ?: return@PageManagerShortcutContext false
              val now = android.os.SystemClock.uptimeMillis()
              if (shortcutDeletePageId == id && now - shortcutDeleteAt <= 3_000L) {
                if (!pv.canDeletePage()) {
                  showNotice(
                    noteText("最后一页不能删除", "The last page cannot be deleted"),
                  )
                } else {
                  pv.deletePage(id)
                }
                shortcutDeletePageId = null
              } else {
                shortcutDeletePageId = id
                shortcutDeleteAt = now
                showNotice(noteText("再次按删除键确认删除当前页", "Press Delete again to confirm"))
              }
            }

            ShortcutAction.PAGE_BOOKMARK -> {
              val value = pv.toggleCurrentPageBookmark()
              showNotice(if (value) noteText("已添加书签", "Bookmark added") else noteText("已取消书签", "Bookmark removed"))
            }

            ShortcutAction.PAGE_CLOSE -> pageManagerOpen = false

            else -> return@PageManagerShortcutContext false
          }
          true
        }.perform(action)

        ShortcutScene.INSERT -> {
          val handled = InsertShortcutContext { insertAction ->
            when (insertAction) {
              ShortcutAction.INSERT_IMAGE -> beginInsertion(ContentKind.IMAGE)

              ShortcutAction.INSERT_TEXT -> beginInsertion(ContentKind.TEXT)

              ShortcutAction.INSERT_LOCAL -> {
                val choosing = insertion.state.value as? InsertionState.ChoosingSource
                  ?: return@InsertShortcutContext false
                if (choosing.kind == ContentKind.IMAGE) {
                  insertion.cancel()
                  imagePicker.launch(arrayOf("image/*"))
                } else {
                  insertion.manualText()
                  showNotice(noteText("请点击页面确定文字位置", "Tap the page to place the text"))
                }
              }

              ShortcutAction.INSERT_NOTELINK -> {
                val choosing = insertion.state.value as? InsertionState.ChoosingSource
                  ?: return@InsertShortcutContext false
                val missing = TransferPermissions.missingNotePermissions(context)
                if (missing.isEmpty()) {
                  appScope.launch { insertion.remote(choosing.kind) }
                } else {
                  permissionRemoteKind = choosing.kind
                  permissionRemoteAuto = false
                  transferPermissionLauncher.launch(missing)
                }
              }

              ShortcutAction.INSERT_CANCEL -> insertion.cancel()

              else -> return@InsertShortcutContext false
            }
            true
          }.perform(action)
          if (handled) pendingFlyoutShortcutAction = action
        }
      }
    }
    EditorToolbar(
      modifier = Modifier.fillMaxSize(),
      state = EditorToolbarState(
        dockEdge = dockEdge,
        dockFraction = dockFraction,
        toolbarHidden = toolbarHidden,
        visibleItems = visibleToolbarItems,
        requestedItem = requestedToolbarItem,
        hardwareShortcutAction = pendingFlyoutShortcutAction,
        toolKind = toolKind,
        pdfStudyMode = gen.let { penView?.isStudyNavigation() ?: true },
        eraserMode = eraserMode,
        canUndo = canUndo,
        canRedo = canRedo,
        hasSelection = hasSelection,
        insertionActive = insertionState !is InsertionState.Idle,
        pageManagerOpen = pageManagerOpen,
        notebookManagerOpen = notebookManagerOpen,
        exportPanelOpen = exportPanelOpen,
        settingsOpen = settingsOpen,
        documentSettingsOpen = documentSettingsOpen,
        debugMode = debugMode,
        penToolbarSettings = penToolbarSettings,
      ),
      actions = EditorToolbarActions(
        onDockEdgeChange = {
          dockEdge = it
          appSettingsStore.toolbarDockEdge = it
        },
        onDockFractionChange = {
          dockFraction = it
          appSettingsStore.toolbarDockFraction = it
        },
        onToolbarHiddenChange = {
          toolbarHidden = it
          appSettingsStore.toolbarHidden = it
        },
        onRequestedItemConsumed = { requestedToolbarItem = null },
        onHardwareShortcutConsumed = { pendingFlyoutShortcutAction = null },
        onShortcutSceneChange = { toolbarShortcutScene = it },
        onToolSelected = { kind ->
          toolKind = kind
          penView?.setTool(kind)
        },
        onPdfFit = {
          if (penView?.fitPdf() != true) {
            showNotice(
              noteText("当前页不是 PDF 源页", "The current page is not a PDF source page"),
            )
          }
        },
        onPdfNavigationModeToggle = {
          val study = penView?.toggleStudyNavigation() ?: true
          showNotice(
            if (study) {
              noteText(
                "学习模式：翻页包含夹纸",
                "Study mode: navigation includes linked notes",
              )
            } else {
              noteText("阅读模式：翻页跳过夹纸", "Reading mode: navigation skips linked notes")
            },
          )
        },
        onEraserModeToggle = {
          eraserMode = if (eraserMode == EraserMode.WHOLE_STROKE) EraserMode.POINT else EraserMode.WHOLE_STROKE
          penView?.setEraserMode(eraserMode)
        },
        onUndo = { penView?.undo() },
        onRedo = { penView?.redo() },
        onDelete = { penView?.deleteSelection() },
        onInsert = beginInsertion,
        onPageManagerToggle = {
          pageManagerOpen = !pageManagerOpen
          notebookManagerOpen = false
          settingsOpen = false
        },
        onPageAdd = {
          penView?.let { pv ->
            pv.addPage()
            showNotice(noteText("已新增第 ${pv.currentPageIndex() + 1} 页", "Added page ${pv.currentPageIndex() + 1}"))
          }
        },
        onPreviousPage = {
          if (penView?.navigatePage(-1) != true) showNotice(noteText("已经是第一页", "Already at the first page"))
        },
        onNextPage = {
          if (penView?.navigatePage(1) != true) showNotice(noteText("已经是最后一页", "Already at the last page"))
        },
        onNotebookManagerToggle = {
          notebookManagerOpen = !notebookManagerOpen
          settingsOpen = false
          pageManagerOpen = false
        },
        onNotebookCurrentPage = {
          penView?.pageIds()?.getOrNull(penView?.currentPageIndex() ?: -1)?.let { pageId ->
            pendingNotebookCreation = NotebookCreationRequest.Transfer(setOf(pageId))
            notebookManagerOpen = false
            settingsOpen = false
            pageManagerOpen = false
          }
        },
        onNotebookCurrentToEnd = {
          penView?.currentPageToEndIds()?.takeIf(Set<UUID>::isNotEmpty)?.let { ids ->
            pendingNotebookCreation = NotebookCreationRequest.Transfer(ids)
            notebookManagerOpen = false
            settingsOpen = false
            pageManagerOpen = false
          }
        },
        onExportPanelToggle = {
          exportPanelOpen = !exportPanelOpen
          if (exportPanelOpen) {
            exportInitialNotebookId = penView?.currentNotebookId()
            exportViewModel.refresh()
          }
          notebookManagerOpen = false
          settingsOpen = false
          pageManagerOpen = false
        },
        onDocumentSettingsOpen = {
          templateCatalog = penView?.refreshTemplates() ?: templateCatalog
          documentSettingsOpen = true
          settingsOpen = false
          notebookManagerOpen = false
          pageManagerOpen = false
        },
        onSettingsOpen = {
          settingsOpen = true
          notebookManagerOpen = false
          pageManagerOpen = false
        },
        onDebugToggle = {
          debugMode = !debugMode
          showNotice(
            if (debugMode) noteText("调试模式已开启", "Debug mode enabled") else noteText("调试模式已关闭", "Debug mode disabled"),
          )
        },
        onPenSlotSelected = { slot ->
          penToolbarSettings = penToolbarSettings.selectSlot(slot)
          penSettingsStore.save(penToolbarSettings)
        },
        onPenSettingsChange = { slot, updated ->
          penToolbarSettings = penToolbarSettings.updateSlot(slot, updated)
          penSettingsStore.save(penToolbarSettings)
        },
        onPenPanelVisibilityChange = { visible ->
          penSettingsOpen = visible
        },
        onToolbarDragStateChange = { active ->
          penView?.setToolbarDragActive(active)
        },
        onInteractionBlockChange = { toolbarInteractionBlocked = it },
      ),
    )

    (insertionState as? InsertionState.ChoosingSource)?.let { choosing ->
      EinkModalOverlay(
        onDismissRequest = insertion::cancel,
        position = EinkModalPosition.TOP,
      ) {
        Column(
          Modifier.fillMaxWidth().padding(20.dp),
        ) {
          Text(
            noteText("插入${contentKindLabel(choosing.kind)}", "Insert ${contentKindLabel(choosing.kind)}"),
            fontSize = 24.sp,
          )
          Button(
            onClick = {
              val missing = TransferPermissions.missingNotePermissions(context)
              if (missing.isEmpty()) {
                permissionRemoteAuto = false
                appScope.launch { insertion.remote(choosing.kind) }
              } else {
                permissionRemoteKind = choosing.kind
                permissionRemoteAuto = false
                transferPermissionLauncher.launch(missing)
              }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            shape = RectangleShape,
          ) {
            Text(
              noteText(
                "从手机取下一项${contentKindLabel(choosing.kind)}",
                "Get next ${contentKindLabel(choosing.kind)} from phone",
              ),
            )
          }
          Button(
            onClick = {
              if (choosing.kind == ContentKind.IMAGE) {
                insertion.cancel();
                imagePicker.launch(arrayOf("image/*"))
              } else {
                insertion.manualText();
                showNotice(noteText("请点击页面确定文字位置", "Tap the page to place the text"))
              }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = RectangleShape,
          ) {
            Text(
              if (choosing.kind == ContentKind.TEXT) {
                noteText(
                  "手动输入",
                  "Enter manually",
                )
              } else {
                noteText("从系统文件选择", "Choose system file")
              },
            )
          }
        }
      }
    }

    (insertionState as? InsertionState.ChoosingClient)?.let { choosing ->
      EinkModalOverlay(onDismissRequest = insertion::cancel, position = EinkModalPosition.TOP) {
        Column(
          Modifier.fillMaxWidth().padding(20.dp),
          verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
          Text(noteText("选择 NoteLink", "Select NoteLink"), fontSize = 24.sp)
          choosing.clients.forEach { available ->
            val count = when (choosing.kind) {
              ContentKind.IMAGE -> available.imageCount
              ContentKind.TEXT -> available.textCount
              ContentKind.PDF -> available.pdfCount
              ContentKind.APP_PACKAGE -> 0
            }
            Button(
              onClick = {
                appScope.launch {
                  insertion.remote(available.client.id, choosing.kind)
                }
              },
              modifier = Modifier.fillMaxWidth(),
              shape = RectangleShape,
            ) {
              Text(noteText("${available.client.name} · $count 项", "${available.client.name} · $count items"))
            }
          }
          EinkDialogAction(noteText("取消", "Cancel"), onClick = insertion::cancel)
        }
      }
    }

    (insertionState as? InsertionState.WaitingForPhone)?.let { waiting ->
      EinkModalOverlay(onDismissRequest = insertion::cancel) {
        Column(
          Modifier.padding(20.dp),
          verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
          Text(noteText("正在查找 NoteLink", "Looking for NoteLink"), fontSize = 20.sp)
          Text(
            noteText(
              "等待 NoteLink 发送${contentKindLabel(waiting.kind)}…",
              "Waiting for NoteLink to send ${contentKindLabel(waiting.kind)}...",
            ),
          )
          Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            EinkDialogAction(noteText("取消", "Cancel"), onClick = insertion::cancel)
          }
        }
      }
    }

    (insertionState as? InsertionState.Error)?.let { error ->
      EinkModalOverlay(onDismissRequest = insertion::dismissError) {
        Column(
          Modifier.padding(20.dp),
          verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
          Text(noteText("NoteLink 传输", "NoteLink transfer"), fontSize = 20.sp)
          Text(error.message)
          Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            EinkDialogAction(noteText("知道了", "OK"), onClick = insertion::dismissError)
          }
        }
      }
    }

    val selectedRichObject = gen.let { penView?.selectedRichObject() }
    selectedRichObject?.let { selectedObject ->
      val objectCenterY =
        (selectedObject.pageBounds.top + selectedObject.pageBounds.bottom) / 2f
      val screenHeightPx = windowSize.height.toFloat()
      val toolbarPosition = if (objectCenterY < screenHeightPx / 2f) {
        Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
      } else {
        Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
      }
      when (selectedObject) {
        is TextObject -> TextPropertiesToolbar(
          text = selectedObject,
          modifier = toolbarPosition,
          onFont = { family ->
            penView?.updateTextObject(selectedObject.id, fontFamily = family)
          },
          onSize = { size ->
            penView?.updateTextObject(selectedObject.id, fontSize = size)
          },
          onEdit = {
            textEditorRequest = TextEditorRequest.Existing(
              selectedObject.id,
              selectedObject.text,
            )
          },
          onDelete = { penView?.deleteSelection() },
          onClose = { penView?.clearRichSelection() },
        )

        is ImageObject -> ImagePropertiesToolbar(
          modifier = toolbarPosition,
          onDelete = { penView?.deleteSelection() },
          onClose = { penView?.clearRichSelection() },
        )

        else -> Unit
      }
    }

    textEditorRequest?.let { request ->
      TextEditorDialog(
        request = request,
        onConfirm = { content ->
          when (request) {
            is TextEditorRequest.New -> insertion.placeText(content, request.x, request.y)
              .onFailure { showNotice("Text insertion failed: ${it.message}") }

            is TextEditorRequest.Existing ->
              penView?.updateTextObject(request.objectId, text = content)
          }
          textEditorRequest = null
        },
        onDismiss = {
          if (request is TextEditorRequest.New) insertion.cancel()
          textEditorRequest = null
        },
      )
    }

    pendingPdfRegion?.let { selection ->
      PdfRegionActionPopup(
        selection = selection,
        onEdit = {
          penView?.activatePdfRegionEdit(selection)
          pendingPdfRegion = null
        },
        onMoveToInbox = {
          pendingPdfRegion = null
          pendingLinkedNoteRequest = PendingLinkedNoteRequest(selection)
        },
      )
    }

    pendingLinkedNoteRequest?.let { request ->
      val targets = penView?.linkedNoteTargetsForCurrentPdfSource().orEmpty()
      val prepare: (UUID?) -> Unit = { targetPageId ->
        pendingLinkedNoteRequest = null
        notebookBusy = true
        penView?.prepareLinkedNoteFromRegion(
          request.selection.normalizedBounds,
          LinkedNoteContent.REGION_IMAGE,
          targetPageId,
        ) { result ->
          notebookBusy = false
          result.onSuccess {
            toolKind = ToolKind.PEN
            penView?.setTool(ToolKind.PEN)
            showNotice(noteText("请在夹纸上点击放置位置", "Tap a location on the linked note"))
          }
        }
      }
      EinkChoiceOverlay(
        onDismissRequest = { pendingLinkedNoteRequest = null },
        title = noteText("选择夹纸", "Choose linked note"),
        description = noteText(
          "新建一张夹纸，或放入当前 PDF 页已有夹纸。",
          "Create a linked note or use one already attached to this PDF page.",
        ),
        choices = buildList {
          add(noteText("新建夹纸", "New linked note") to { prepare(null) })
          targets.forEach { target ->
            add(
              noteText("夹纸 ${target.ordinal}", "Linked note ${target.ordinal}") to { prepare(target.pageId) },
            )
          }
        },
      )
    }

    if (penView?.hasPendingLinkedNotePlacement() == true) {
      Surface(
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
          .border(1.dp, Color.Black, RectangleShape),
        shape = RectangleShape,
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
      ) {
        Text(
          noteText("点击夹纸确定放置位置 · 返回键取消", "Tap the linked note to place · Back to cancel"),
          Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
      }
    }

    val pages = gen.let { penView?.pageUiItems().orEmpty() }
    val currentPageIndex = gen.let { penView?.currentPageIndex() ?: 0 }
    val currentPageId = pages.getOrNull(currentPageIndex)?.id
    val bookmarked = gen.let { penView?.currentPageBookmarked() ?: false }
    Surface(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(12.dp)
        .penInputGuard { pageControlInteractionBlocked = it }
        .stylusModifiedClickable(
          onSide1Click = {
            penView?.let { pv ->
              val isBookmarked = pv.toggleCurrentPageBookmark()
              showNotice(
                if (isBookmarked) noteText("已添加书签", "Bookmark added") else noteText("已取消书签", "Bookmark removed"),
              )
            }
          },
        ),
      color = Color(0xDDF2F2F2),
      contentColor = Color.Black,
      tonalElevation = 3.dp,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (bookmarked) {
          Icon(Icons.Filled.Bookmark, contentDescription = "Bookmarked")
        }
        Text("${currentPageIndex + 1}/${pages.size.coerceAtLeast(1)}")
      }
    }

    if (pageManagerOpen && penView != null) {
      val managerModifier = when (dockEdge) {
        DockEdge.START -> Modifier.align(Alignment.CenterStart)
          .padding(start = 52.dp, top = 8.dp, bottom = 8.dp)
          .width(thumbnailSize.width + PAGE_MANAGER_SIDE_EXTRA_WIDTH).fillMaxHeight()

        DockEdge.END -> Modifier.align(Alignment.CenterEnd)
          .padding(end = 52.dp, top = 8.dp, bottom = 8.dp)
          .width(thumbnailSize.width + PAGE_MANAGER_SIDE_EXTRA_WIDTH).fillMaxHeight()

        DockEdge.TOP -> Modifier.align(Alignment.TopCenter)
          .padding(start = 8.dp, end = 8.dp, top = 52.dp)
          .fillMaxWidth().height(
            PAGE_MANAGER_HEADER_HEIGHT + thumbnailSize.height +
              PAGE_MANAGER_CONTENT_PADDING * 2 + 2.dp,
          )

        DockEdge.BOTTOM -> Modifier.align(Alignment.BottomCenter)
          .padding(start = 8.dp, end = 8.dp, bottom = 52.dp)
          .fillMaxWidth().height(
            PAGE_MANAGER_HEADER_HEIGHT + thumbnailSize.height +
              PAGE_MANAGER_CONTENT_PADDING * 2 + 2.dp,
          )
      }
      PageManagerPanel(
        state = PageManagerPanelState(
          modifier = managerModifier,
          dockEdge = dockEdge,
          thumbnailSize = thumbnailSize,
          pages = pages,
          currentPageId = currentPageId,
        ),
        actions = PageManagerPanelActions(
          thumbnail = { id -> penView?.pageThumbnail(id) },
          requestThumbnails = { ids -> penView?.requestThumbnails(ids) },
          onSelect = { id ->
            penView?.switchToPage(id)
            pageManagerOpen = false
          },
          onAddAfter = { id ->
            val newId = penView!!.addPageAfter(id, activate = true)
            showNotice(
              noteText(
                "已新增第 ${penView!!.currentPageIndex() + 1} 页",
                "Added page ${penView!!.currentPageIndex() + 1}",
              ),
            )
            newId
          },
          onDelete = { id ->
            val pv = penView!!
            if (!pv.canDeletePage()) {
              showNotice(noteText("最后一页不能删除", "The last page cannot be deleted"))
              false
            } else {
              pv.deletePage(id)
            }
          },
          onMove = { id, target -> penView!!.movePage(id, target) },
          onNotice = showNotice,
        ),
      )
    }

    val notebookSummaries = if (notebookManagerOpen && penView != null) {
      gen.let { runCatching { penView!!.notebookSummaries() }.getOrElse { emptyList() } }
    } else {
      emptyList()
    }
    if (notebookManagerOpen && penView != null) {
      NotebookManagerScreen(
        state = NotebookManagerState(
          summaries = notebookSummaries,
          pages = pages,
          currentToEndIds = penView!!.currentPageToEndIds(),
          busy = notebookBusy,
          thumbnailSize = notebookThumbnailSize,
        ),
        actions = NotebookManagerActions(
          coverBitmap = { summary -> penView!!.notebookCoverThumbnail(summary) },
          pageBitmap = { id -> penView!!.pageThumbnail(id) },
          requestCovers = { summaries -> penView!!.requestNotebookCovers(summaries) },
          requestPages = { ids -> penView!!.requestThumbnails(ids) },
          onSwitchNotebook = { id ->
            if (id == penView!!.currentNotebookId()) {
              notebookManagerOpen = false
            } else {
              notebookBusy = true
              penView!!.switchNotebook(id) { result ->
                notebookBusy = false
                if (result.isSuccess) {
                  notebookManagerOpen = false
                  showNotice(noteText("已切换笔记本", "Notebook switched"))
                }
              }
            }
          },
          onCreateFromCurrentToEnd = { ids ->
            pendingNotebookCreation = NotebookCreationRequest.Transfer(ids)
          },
          onCreateFromSelection = { ids ->
            pendingNotebookCreation = NotebookCreationRequest.Transfer(ids)
          },
          onCreateBlank = {
            pendingNotebookCreation = NotebookCreationRequest.Blank
          },
          onImportLocalPdf = { uri ->
            notebookBusy = true
            penView!!.importPdf(uri) { result ->
              notebookBusy = false
              result.onSuccess {
                notebookManagerOpen = false
                showNotice(noteText("PDF 已导入", "PDF imported"))
              }
            }
          },
          onImportNoteLinkPdf = {
            notebookManagerOpen = false
            val missing = TransferPermissions.missingNotePermissions(context)
            if (missing.isEmpty()) {
              appScope.launch { insertion.remote(ContentKind.PDF) }
            } else {
              permissionRemoteKind = ContentKind.PDF
              permissionRemoteAuto = false
              transferPermissionLauncher.launch(missing)
            }
          },
          onDeleteNotebook = { id ->
            notebookBusy = true
            penView!!.deleteNotebook(id) { result ->
              notebookBusy = false
              if (result.isSuccess) showNotice(noteText("已删除笔记本", "Notebook deleted"))
            }
          },
          onExportNotebook = { id ->
            exportInitialNotebookId = id
            exportCreationRequest++
            notebookManagerOpen = false
            exportPanelOpen = true
            exportViewModel.refresh()
          },
          onNotice = showNotice,
          onClose = { if (!notebookBusy) notebookManagerOpen = false },
        ),
      )
    }

    if (settingsOpen) {
      SettingsScreen(
        state = SettingsState(
          debugMode = debugMode,
          startupBehavior = startupBehavior,
          pairedClients = pairedClients,
          onlineClients = onlineNoteLinks,
          pairingCandidates = pairingCandidates,
          pairingScanActive = pairingScanActive,
          pairingInProgress = pairingInProgress,
          transferStatus = transferStatus,
          transferSnapshot = transferSnapshot,
          transferEvents = transferEvents,
          transferEndpointName = pairedClients.firstOrNull { it.id == transferSnapshot.deviceId }?.name,
          transferPermissionsGranted = missingTransferPermissions.isEmpty(),
          skipSourceSelectionWhenQueueAvailable = skipSourceSelectionWhenQueueAvailable,
          autoCreatePageOnNextAtEnd = autoCreatePageOnNextAtEnd,
          showRecentTransferEvents = showRecentTransferEvents,
          language = language,
          visibleToolbarItems = visibleToolbarItems,
          shortcutBindings = shortcutBindings,
          shortcutBindingRequest = shortcutBindingRequest,
          updateUiState = updateUiState,
          noteAppUpdateState = noteAppUpdateState,
        ),
        actions = SettingsActions(
          onDebugModeChange = { debugMode = it },
          onStartupBehaviorChange = { behavior ->
            runCatching { penView?.setStartupBehavior(behavior) }
              .onSuccess {
                startupBehavior = behavior
                showNotice(noteText("启动行为已保存", "Startup behavior saved"))
              }
              .onFailure { showNotice("Could not save setting: ${it.message}") }
          },
          onSkipSourceSelectionChange = {
            skipSourceSelectionWhenQueueAvailable = it
            appSettingsStore.skipSourceSelectionWhenQueueAvailable = it
          },
          onAutoCreatePageOnNextAtEndChange = {
            autoCreatePageOnNextAtEnd = it
            appSettingsStore.autoCreatePageOnNextAtEnd = it
            penView?.autoCreatePageOnNextAtEnd = it
          },
          onShowRecentTransferEventsChange = {
            showRecentTransferEvents = it
            appSettingsStore.showRecentTransferEvents = it
          },
          onLanguageChange = {
            appSettingsStore.language = it
            language = it
          },
          onToolbarItemVisibilityChange = { item, visible ->
            visibleToolbarItems = if (visible) visibleToolbarItems + item else visibleToolbarItems - item
            appSettingsStore.visibleToolbarItems = visibleToolbarItems
          },
          onResetToolbarItems = {
            visibleToolbarItems = ToolbarItem.defaults
            appSettingsStore.visibleToolbarItems = visibleToolbarItems
          },
          onStartShortcutCapture = { shortcutCaptureScene = it },
          onShortcutBindingRequestConsumed = { shortcutBindingRequest = null },
          onShortcutBind = { scene, key, action ->
            shortcutBindings = shortcutBindings.bind(scene, key, action)
            appSettingsStore.saveHardwareShortcut(scene, key, action)
          },
          onShortcutSceneClear = { scene ->
            shortcutBindings = shortcutBindings.clear(scene)
            appSettingsStore.clearHardwareShortcuts(scene)
          },
          onScanClients = {
            if (missingTransferPermissions.isNotEmpty()) {
              showNotice("Grant Nearby devices permission first")
            } else if (!pairingScanActive) {
              pairingScanActive = true
              appScope.launch {
                runCatching { phoneTransfer.discoverPairingCandidates() }
                  .onSuccess { pairingCandidates = it }
                  .onFailure { showNotice("Scan failed: ${it.message}") }
                onlineNoteLinks = runCatching {
                  phoneTransfer.discoverAvailable(timeoutMillis = 2_000L) { onlineNoteLinks = it }
                }.getOrDefault(emptyList())
                pairingScanActive = false
              }
            }
          },
          onPairClient = { candidate, code ->
            appScope.launch {
              pairingInProgress = true
              EventLog.log(
                "NoteLinkPairing",
                "start device=${candidate.deviceId} address=${candidate.bluetoothAddress}",
              )
              runCatching { phoneTransfer.pair(candidate, code) }
                .onSuccess {
                  suppressNextOnlineDiscovery = true
                  pairedClients = phoneTransfer.pairing.pairedClients
                  pairingCandidates = pairingCandidates.filterNot { value -> value.deviceId == it.id }
                  onlineNoteLinks = emptyList()
                  transferStatusRevision++
                  EventLog.log("NoteLinkPairing", "complete device=${it.id}")
                  showNotice(noteText("${it.name} 配对完成", "Paired with ${it.name}"))
                }
                .onFailure {
                  EventLog.log("NoteLinkPairing", "failed device=${candidate.deviceId} error=${it.message}")
                  showNotice("Pairing failed: ${it.message}")
                }
              pairingInProgress = false
            }
          },
          onRenameClient = { id, name ->
            runCatching { phoneTransfer.pairing.rename(id, name) }
              .onSuccess {
                pairedClients = phoneTransfer.pairing.pairedClients
                showNotice(noteText("名称已保存", "Name saved"))
              }
              .onFailure { showNotice("Rename failed: ${it.message}") }
          },
          onUnpairClient = { id ->
            phoneTransfer.pairing.unpair(id)
            pairedClients = phoneTransfer.pairing.pairedClients
            transferStatusRevision++
            showNotice(noteText("已移除 NoteLink 配对", "NoteLink pairing removed"))
          },
          onTransferPermissions = {
            permissionRemoteKind = null
            permissionRemoteAuto = false
            transferPermissionLauncher.launch(TransferPermissions.missingNotePermissions(context))
          },
          onRefreshTransferStatus = {
            transferStatusRevision++
            showNotice(noteText("连接状态已刷新", "Connection status refreshed"))
          },
          onCancelTransfer = phoneTransfer::cancel,
          onCheckUpdate = { checkForUpdates(true) },
          onOpenRelease = openRelease,
          onUpdateViaNoteLink = updateViaNoteLink,
          onContinueNoteAppInstall = {
            runCatching { noteAppInstaller.launch() }
              .onFailure { noteAppUpdateState = NoteAppUpdateUiState.Failed(it.message ?: "Cannot open installer") }
          },
          onClose = { settingsOpen = false },
        ),
      )
    }

    if (exportPanelOpen && penView != null) {
      val pv = penView!!
      val currentNotebookId = pv.currentNotebookId()
      ExportManagerScreen(
        input = ExportManagerInput(
          viewModel = exportViewModel,
          initialNotebookId = exportInitialNotebookId ?: currentNotebookId,
          creationRequest = exportCreationRequest,
          currentNotebookId = currentNotebookId,
          pairedClients = pairedClients,
          callbacks = ExportManagerCallbacks(
            discoverClients = {
              if (pairedClients.isEmpty()) {
                exportPanelOpen = false
                settingsOpen = true
                error(noteText("请先在设置中配对 NoteLink", "Pair NoteLink in Settings first"))
              }
              val missing = TransferPermissions.missingNotePermissions(context)
              if (missing.isNotEmpty()) {
                val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
                exportPermissionResult = result
                transferPermissionLauncher.launch(missing)
                try {
                  check(result.await()) { noteText("需要附近设备权限", "Nearby devices permission is required") }
                } finally {
                  if (exportPermissionResult === result) exportPermissionResult = null
                }
              }
              phoneTransfer.discoverAvailable().map { it.client }
            },
            pageBitmap = { id -> pv.pageThumbnail(id) },
            requestThumbnails = { ids -> pv.requestThumbnails(ids) },
            beforeExport = { notebookId ->
              if (notebookId == pv.currentNotebookId()) {
                pv.flushPersistenceForExport()
              } else {
                true
              }
            },
            sendToNoteLink = { clientId, artifact, progress ->
              phoneTransfer.sendExport(clientId, artifact, progress)
            },
            onNotice = showNotice,
            onClose = { exportPanelOpen = false },
          ),
        ),
      )
    }

    if (documentSettingsOpen && penView != null) {
      val pv = penView!!
      DocumentSettingsOverlay(
        template = templateCatalog.find(pv.currentPageTemplateId()),
        templateEnabled = pv.currentPageKind() != PageKind.PDF_SOURCE,
        preview = pv::templatePreview,
        onTemplate = {
          templateCatalog = pv.refreshTemplates()
          templateChooserOpen = true
        },
        onDismiss = { documentSettingsOpen = false },
      )
    }

    if (templateChooserOpen && penView != null) {
      val pv = penView!!
      val pageSize = pv.currentPageSize()
      TemplateChooserOverlay(
        state = TemplateChooserState(
          catalog = templateCatalog,
          selectedId = pv.currentPageTemplateId(),
          pageWidth = pageSize.first,
          pageHeight = pageSize.second,
          preview = pv::templatePreview,
        ),
        actions = TemplateChooserActions(
          onSelect = { id ->
            if (pv.setCurrentPageTemplate(id)) {
              templateChooserOpen = false
              showNotice(
                noteText(
                  "Template 已更换为 ${templateCatalog.find(id)?.name ?: id}",
                  "Template changed to ${templateCatalog.find(id)?.name ?: id}",
                ),
              )
              exportViewModel.refresh()
            } else {
              showNotice(noteText("该 Template 与当前页面不兼容", "This template is not compatible with the current page"))
            }
          },
          onConnectDirectory = { templateDirectoryPicker.launch(null) },
          onRefresh = { templateCatalog = pv.refreshTemplates(force = true) },
          onDismiss = { templateChooserOpen = false },
        ),
      )
    }

    pendingNotebookCreation?.let { request ->
      val pvForDialog = penView
      var pageSize by remember(pvForDialog) { mutableStateOf(pvForDialog?.newNotebookPageSize() ?: (0f to 0f)) }
      DisposableEffect(pvForDialog) {
        val listener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
          pageSize = pvForDialog?.newNotebookPageSize() ?: (0f to 0f)
        }
        pvForDialog?.addOnLayoutChangeListener(listener)
        onDispose { pvForDialog?.removeOnLayoutChangeListener(listener) }
      }
      NotebookNameDialog(
        state = NotebookNameDialogState(
          catalog = templateCatalog,
          pageWidth = pageSize.first,
          pageHeight = pageSize.second,
          templateEnabled = request == NotebookCreationRequest.Blank,
        ),
        actions = NotebookNameDialogActions(
          preview = { id, previewWidth, previewHeight ->
            pvForDialog?.templatePreview(id, previewWidth, previewHeight)
          },
          onConnectDirectory = { templateDirectoryPicker.launch(null) },
          onRefresh = {
            templateCatalog = pvForDialog?.refreshTemplates(force = true) ?: templateCatalog
          },
          onConfirm = { title, templateId ->
            pendingNotebookCreation = null
            val pv = penView
            if (pv != null) {
              notebookBusy = true
              val complete: (Result<UUID>) -> Unit = { result ->
                notebookBusy = false
                if (result.isSuccess) {
                  notebookManagerOpen = false
                  showNotice(noteText("已创建笔记本“$title”", "Created notebook \"$title\""))
                } else {
                  showNotice(result.exceptionOrNull()?.message ?: noteText("创建失败", "Creation failed"))
                }
              }
              when (request) {
                NotebookCreationRequest.Blank -> pv.createBlankNotebook(title, templateId, complete, pageSize)

                is NotebookCreationRequest.Transfer ->
                  pv.transferPagesToNewNotebook(request.pageIds, title, complete)
              }
            }
          },
          onDismiss = { if (!notebookBusy) pendingNotebookCreation = null },
        ),
      )
    }

    val visibleUpdate = updateUiState.visibleUpdate
    LaunchedEffect(visibleUpdate) {
      if (visibleUpdate == null) {
        updateNotificationState.dismiss()
      } else {
        updateNotificationState.show(
          title = noteText(
            "发现新版本 ${visibleUpdate.latestVersion.display}",
            "New version ${visibleUpdate.latestVersion.display}",
          ),
          message = visibleUpdate.releaseTitle,
          duration = InAppNotificationDuration.LONG,
          actionLabel = noteText("查看 Release", "View release"),
          onAction = { openRelease(visibleUpdate.releaseUrl) },
          onDismiss = { updateUiState = updateUiState.dismissBanner() },
        )
      }
    }
    Column(
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).width(620.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (
        !settingsOpen &&
        transferSnapshot.phase.isActiveTransferPhase &&
        transferSnapshot.phase != TransferPhase.AWAITING_COMMIT
      ) {
        ActiveTransferOverlay(
          snapshot = transferSnapshot,
          endpointName = pairedClients.firstOrNull { it.id == transferSnapshot.deviceId }?.name,
          onCancel = phoneTransfer::cancel,
          modifier = Modifier.width(460.dp),
        )
      }

      InAppNotificationHost(
        state = notificationState,
        modifier = Modifier.fillMaxWidth()
          .penInputGuard { notificationInteractionBlocked = it },
      )

      if (!settingsOpen && !transferSnapshot.phase.isActiveTransferPhase) {
        InAppNotificationHost(
          state = updateNotificationState,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun ActiveTransferOverlay(
  snapshot: TransferSnapshot,
  endpointName: String?,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.border(2.dp, Color(0xFF4E5F70), RectangleShape),
    shape = RectangleShape,
    color = Color.White,
    tonalElevation = 6.dp,
    shadowElevation = 8.dp,
  ) {
    Column(Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
            listOfNotNull(snapshot.phase.name.replace('_', ' '), snapshot.mode?.name, "SSID ${snapshot.ssidMatch.name}")
              .joinToString(" · "),
            fontSize = 16.sp,
          )
          Text(
            snapshot.lastFailure?.let { "${it.code}: ${it.message}" }
              ?: snapshot.endpoint?.let {
                "${endpointName ?: snapshot.deviceId ?: noteText("未知设备", "Unknown device")} · ${it.host}:${it.port}"
              }
              ?: noteText("正在通过 BLE 协商数据通道", "Negotiating the data channel over BLE"),
            fontSize = 11.sp,
            color = Color.DarkGray,
          )
        }
        if (snapshot.canCancel) {
          IconButton(onClick = onCancel) { Icon(Icons.Default.Cancel, noteText("取消传输", "Cancel transfer")) }
        }
      }
      if (snapshot.totalBytes > 0) {
        LinearProgressIndicator(
          progress = { (snapshot.bytesTransferred.toFloat() / snapshot.totalBytes).coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          "${snapshot.bytesTransferred}/${snapshot.totalBytes} B · ${snapshot.bytesPerSecond} B/s" +
            (snapshot.etaMillis?.let { " · ETA ${it / 1000}s" } ?: ""),
          fontSize = 11.sp,
        )
      }
    }
  }
}

private fun noteTransferStatus(context: Context, isPaired: Boolean, permissionsGranted: Boolean): String {
  if (!context.packageManager.hasSystemFeature(
      PackageManager.FEATURE_BLUETOOTH_LE,
    )
  ) {
    return "This device does not support BLE"
  }
  if (!context.packageManager.hasSystemFeature(
      PackageManager.FEATURE_WIFI_DIRECT,
    )
  ) {
    return "This device does not support Wi-Fi Direct"
  }
  if (!permissionsGranted) return "Nearby devices permission required"
  if (context.getSystemService(
      BluetoothManager::class.java,
    )?.adapter?.isEnabled != true
  ) {
    return "Bluetooth is off"
  }
  if (context.getSystemService(
      WifiManager::class.java,
    )?.isWifiEnabled != true
  ) {
    return "Wi-Fi is off"
  }
  return if (isPaired) {
    noteText(
      "已就绪，插入时按需连接手机",
      "Ready; connects to the phone when needed",
    )
  } else {
    "Pair with a phone first"
  }
}

private const val THUMBNAIL_SCREEN_SCALE = 10f
private const val NOTEBOOK_THUMBNAIL_SCREEN_SCALE = 4f

@Composable
private fun TextPropertiesToolbar(
  text: TextObject,
  modifier: Modifier = Modifier,
  onFont: (TextFontFamily) -> Unit,
  onSize: (Float) -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onClose: () -> Unit,
) {
  Surface(
    modifier = modifier.border(2.dp, Color(0xFF4E5F70), RectangleShape),
    shape = RectangleShape,
    color = Color(0xFFF2F2F2),
    tonalElevation = 6.dp,
    shadowElevation = 8.dp,
  ) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(noteText("字体", "Font"), modifier = Modifier.padding(horizontal = 6.dp))
        TextFontFamily.entries.forEach { family ->
          TextButton(onClick = { onFont(family) }) {
            Text(
              when (family) {
                TextFontFamily.SANS_SERIF -> noteText("无衬线", "Sans serif")
                TextFontFamily.SERIF -> noteText("衬线", "Serif")
                TextFontFamily.MONOSPACE -> noteText("等宽", "Monospace")
              },
              color = if (family == text.fontFamily) Color.Black else Color.Gray,
            )
          }
        }
        TextButton(onClick = onEdit) { Text(noteText("编辑文字", "Edit text")) }
        IconButton(onClick = onDelete) {
          Icon(Icons.Filled.Delete, contentDescription = noteText("删除文字", "Delete text"))
        }
        TextButton(onClick = onClose) { Text(noteText("完成", "Done")) }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(noteText("字号", "Size"), modifier = Modifier.padding(horizontal = 6.dp))
        listOf(16f, 24f, 32f, 48f, 56f, 64f).forEach { size ->
          TextButton(onClick = { onSize(size) }) {
            Text(
              size.toInt().toString(),
              color = if (size == text.fontSize) Color.Black else Color.Gray,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ImagePropertiesToolbar(modifier: Modifier = Modifier, onDelete: () -> Unit, onClose: () -> Unit) {
  Surface(
    modifier = modifier.border(2.dp, Color(0xFF4E5F70), RectangleShape),
    shape = RectangleShape,
    color = Color(0xFFF2F2F2),
    tonalElevation = 6.dp,
    shadowElevation = 8.dp,
  ) {
    Row(
      modifier = Modifier.padding(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onDelete) {
        Icon(Icons.Filled.Delete, contentDescription = noteText("删除图片", "Delete image"))
      }
      IconButton(onClick = onClose) {
        Icon(Icons.Filled.Check, contentDescription = noteText("完成编辑图片", "Finish editing image"))
      }
    }
  }
}

@Composable
private fun TextEditorDialog(request: TextEditorRequest, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
  var value by remember(request) { mutableStateOf(request.initialText) }
  val focusRequester = remember(request) { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  LaunchedEffect(request) {
    focusRequester.requestFocus()
    keyboard?.show()
  }
  EinkModalOverlay(onDismissRequest = onDismiss, position = EinkModalPosition.TOP) {
    Column(
      Modifier.padding(20.dp),
      verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
    ) {
      Text(
        if (request is TextEditorRequest.New) noteText("插入文字", "Insert text") else noteText("编辑文字", "Edit text"),
        fontSize = 20.sp,
      )
      OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        label = { Text(noteText("文字内容", "Text")) },
        minLines = 3,
        maxLines = 8,
        shape = RectangleShape,
      )
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, Alignment.End),
      ) {
        EinkDialogAction(noteText("取消", "Cancel"), onClick = onDismiss)
        EinkDialogAction(noteText("确定", "OK"), enabled = value.isNotBlank()) {
          if (value.isNotBlank()) onConfirm(value)
        }
      }
    }
  }
}

@Composable
private fun LogOverlay(modifier: Modifier = Modifier) {
  val listState = rememberLazyListState()
  LaunchedEffect(EventLog.lines.size) {
    if (EventLog.lines.isNotEmpty()) listState.scrollToItem(EventLog.lines.size - 1)
  }
  Box(modifier.background(Color(0xCCFFFFFF))) {
    LazyColumn(state = listState) {
      items(EventLog.lines) { line ->
        Text(
          line,
          color = Color.Black,
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
        )
      }
    }
  }
}

package com.betterhv.note

import android.os.Bundle
import android.os.Build
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.TextFontFamily
import com.betterhv.note.doc.TextObject
import com.betterhv.note.storage.ImportedImage
import com.betterhv.transfer.android.TransferPermissions
import com.betterhv.transfer.core.ContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.betterhv.note.storage.StartupBehavior
import com.betterhv.note.export.ExportViewModel
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var penView: PenDrawView? = null
    private var releaseTransientInputGuards: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.log("MainActivity", "onCreate")
        NativeSelfTest.run()
        setContent {
            AppRoot(
                onView = { penView = it },
                onTransientInputGuardReleaseReady = { releaseTransientInputGuards = it }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        PenButtonTracker.observeKey(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        releaseTransientInputGuards = null
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    onView: (PenDrawView) -> Unit,
    onTransientInputGuardReleaseReady: ((() -> Unit)?) -> Unit
) {
    val context = LocalContext.current
    val exportViewModel: ExportViewModel = viewModel()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val thumbnailSize = DpSize(
        width = configuration.screenWidthDp.dp / THUMBNAIL_SCREEN_SCALE,
        height = configuration.screenHeightDp.dp / THUMBNAIL_SCREEN_SCALE
    )
    val notebookThumbnailSize = DpSize(
        width = configuration.screenWidthDp.dp / NOTEBOOK_THUMBNAIL_SCREEN_SCALE,
        height = configuration.screenHeightDp.dp / NOTEBOOK_THUMBNAIL_SCREEN_SCALE
    )
    val penSettingsStore = remember(context) { PenSettingsStore(context) }
    val appSettingsStore = remember(context) { AppSettingsStore(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val phoneTransfer = remember(context) { PhoneTransferClient(context) }
    val insertion = remember(phoneTransfer) { InsertionCoordinator(phoneTransfer) }
    val insertionState by insertion.state.collectAsState()
    DisposableEffect(insertion) { onDispose { insertion.close() } }
    val showNotice: (String) -> Unit = remember(snackbarHostState, snackbarScope) {
        { message ->
            snackbarScope.launch {
                snackbarHostState.showSnackbar(
                    message = message,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
            }
        }
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
        var dockEdge by rememberSaveable { mutableStateOf(DockEdge.START) }
        var pageManagerOpen by rememberSaveable { mutableStateOf(false) }
        var penSettingsOpen by remember { mutableStateOf(false) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var notebookManagerOpen by rememberSaveable { mutableStateOf(false) }
        var exportPanelOpen by rememberSaveable { mutableStateOf(false) }
        var exportInitialNotebookId by remember { mutableStateOf<UUID?>(null) }
        var exportCreationRequest by rememberSaveable { mutableStateOf(0L) }
        var notebookBusy by remember { mutableStateOf(false) }
        var pendingNotebookCreation by remember { mutableStateOf<NotebookCreationRequest?>(null) }
        var textEditorRequest by remember { mutableStateOf<TextEditorRequest?>(null) }
        var toolbarInteractionBlocked by remember { mutableStateOf(false) }
        var pageControlInteractionBlocked by remember { mutableStateOf(false) }
        var snackbarInteractionBlocked by remember { mutableStateOf(false) }
        var debugInteractionBlocked by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            onTransientInputGuardReleaseReady {
                toolbarInteractionBlocked = false
                pageControlInteractionBlocked = false
                snackbarInteractionBlocked = false
                debugInteractionBlocked = false
            }
            onDispose { onTransientInputGuardReleaseReady(null) }
        }
        var startupBehavior by remember { mutableStateOf(StartupBehavior.WORKING_COPY) }
        var skipSourceSelectionWhenQueueAvailable by remember {
            mutableStateOf(appSettingsStore.skipSourceSelectionWhenQueueAvailable)
        }
        var autoCreatePageOnNextAtEnd by remember {
            mutableStateOf(appSettingsStore.autoCreatePageOnNextAtEnd)
        }
        var pairedClients by remember { mutableStateOf(phoneTransfer.pairing.pairedClients) }
        var onlineNoteLinks by remember {
            mutableStateOf<List<PhoneTransferClient.AvailableNoteLink>>(emptyList())
        }
        var pairingCandidates by remember {
            mutableStateOf<List<PhoneTransferClient.PairingCandidate>>(emptyList())
        }
        var pairingScanActive by remember { mutableStateOf(false) }
        var transferStatusRevision by remember { mutableIntStateOf(0) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            var stopped = false
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> stopped = true
                    Lifecycle.Event.ON_RESUME -> {
                        if (stopped) {
                            stopped = false
                            snackbarScope.launch {
                                phoneTransfer.recoverAfterWake()
                                penView?.recoverAfterWake()
                                transferStatusRevision++
                            }
                        } else {
                            penView?.recoverAfterWake()
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
        val transferStatus = remember(transferStatusRevision, pairedClients) {
            noteTransferStatus(context, pairedClients.isNotEmpty(), missingTransferPermissions.isEmpty())
        }
        LaunchedEffect(transferStatusRevision, pairedClients) {
            onlineNoteLinks = if (missingTransferPermissions.isEmpty() && pairedClients.isNotEmpty()) {
                runCatching {
                    phoneTransfer.discoverAvailable(timeoutMillis = 3_000L) { onlineNoteLinks = it }
                }.getOrDefault(emptyList())
            } else emptyList()
        }

        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            snackbarScope.launch {
                insertion.localImage(uri)
                if (insertion.state.value is InsertionState.ImageReady) showNotice("图片已导入，请点击页面确定位置")
            }
        }
        var permissionRemoteKind by remember { mutableStateOf<ContentKind?>(null) }
        var permissionRemoteAuto by remember { mutableStateOf(false) }
        val transferPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            transferStatusRevision++
            val kind = permissionRemoteKind.also { permissionRemoteKind = null }
            val auto = permissionRemoteAuto.also { permissionRemoteAuto = false }
            if (kind != null && TransferPermissions.hasAllNotePermissions(context)) {
                snackbarScope.launch {
                    if (auto) insertion.remoteIfAvailableOrChoose(kind) else insertion.remote(kind)
                }
            } else if (kind != null) showNotice("需要附近设备权限才能从手机获取")
        }

        val beginInsertion: (ContentKind) -> Unit = { kind ->
            if (!skipSourceSelectionWhenQueueAvailable) {
                insertion.choose(kind)
            } else {
                val missing = TransferPermissions.missingNotePermissions(context)
                if (missing.isEmpty()) {
                    snackbarScope.launch { insertion.remoteIfAvailableOrChoose(kind) }
                } else {
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
                    startupBehavior = it.startupBehavior()
                    it.autoCreatePageOnNextAtEnd = autoCreatePageOnNextAtEnd
                    insertion.attach(it)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        LaunchedEffect(penView, activePenStyle) {
            penView?.setPenStyle(activePenStyle)
        }
        // Eraser input layer: a non-hvpen view stacked above the pen canvas so it
        // receives the full touch stream the ROM withholds from the hvpen-
        // registered PenDrawView. It only acts on the eraser tool; pen events
        // fall through to PenDrawView. See EraserOverlayView.
        penView?.let { pv ->
            AndroidView(
                factory = { ctx -> EraserOverlayView(ctx, pv) },
                modifier = Modifier.fillMaxSize()
            )
            // hvNote uses its non-hvpen MemoMarkView for lasso gestures.  Keep
            // the same separation here: disabling ROM ink for lasso also stops
            // hvpen point callbacks, while this overlay still receives the full
            // standard Android touch stream. It is above the eraser overlay but
            // lets tail-eraser events fall through to it.
            AndroidView(
                factory = { ctx -> LassoOverlayView(ctx, pv) },
                modifier = Modifier.fillMaxSize()
            )
            AndroidView(
                factory = { ctx -> PageControlOverlayView(ctx, pv, showNotice) },
                modifier = Modifier.fillMaxSize()
            )
            AndroidView(
                factory = {
                    ObjectEditOverlayView(it, pv).also { overlay ->
                        overlay.onPlacementTap = { x, y ->
                            when (val request = insertion.state.value) {
                                is InsertionState.ImageReady -> insertion.placeImage(x, y)
                                    .onSuccess { showNotice("图片已插入；使用 Side1 点击可编辑") }
                                    .onFailure { showNotice("图片插入失败：${it.message}") }
                                is InsertionState.TextReady ->
                                    textEditorRequest = TextEditorRequest.New(x, y, request.initialText)
                                else -> Unit
                            }
                        }
                    }
                },
                update = { overlay ->
                    overlay.onPlacementTap = if (
                        insertionState is InsertionState.ImageReady || insertionState is InsertionState.TextReady
                    ) { x, y ->
                        when (val request = insertion.state.value) {
                            is InsertionState.ImageReady -> insertion.placeImage(x, y)
                                .onSuccess { showNotice("图片已插入；使用 Side1 点击可编辑") }
                                .onFailure { showNotice("图片插入失败：${it.message}") }
                            is InsertionState.TextReady ->
                                textEditorRequest = TextEditorRequest.New(x, y, request.initialText)
                            else -> Unit
                        }
                    } else null
                    pv.setOnTextEditRequested { text ->
                        textEditorRequest = TextEditorRequest.Existing(text.id, text.text)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        val uiInputBlockState = UiInputBlockState(
            pageManagerOpen = pageManagerOpen,
            toolbarPopupOpen = penSettingsOpen,
            settingsOpen = settingsOpen,
            notebookManagerOpen = notebookManagerOpen,
            notebookNameOpen = pendingNotebookCreation != null,
            insertionOpen = insertionState !is InsertionState.Idle,
            textEditorOpen = textEditorRequest != null,
            notebookBusy = notebookBusy,
            exportPanelOpen = exportPanelOpen,
            richObjectSelected = penView?.selectedRichObject() != null,
            toolbarInteraction = toolbarInteractionBlocked,
            pageControlInteraction = pageControlInteractionBlocked,
            snackbarInteraction = snackbarInteractionBlocked,
            debugInteraction = debugInteractionBlocked
        )
        LaunchedEffect(penView, uiInputBlockState, docVersion) {
            penView?.setUiInputBlocked(uiInputBlockState.blocked)
        }

        BackHandler(
            enabled = !notebookBusy && (
                textEditorRequest != null || insertionState !is InsertionState.Idle || pendingNotebookCreation != null ||
                    settingsOpen || notebookManagerOpen || pageManagerOpen || exportPanelOpen
                )
        ) {
            when {
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
            }
        }

        // Debug-only affordances: the event-log overlay and the Flush/Clear
        // buttons used to exercise materialize()/clear() without a real
        // erase or page-turn gesture. Hidden by default so the toolbar's
        // Menu > Debug mode toggle controls whether they show.
        if (debugMode) {
            LogOverlay(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).width(360.dp)
            )
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .penInputGuard { debugInteractionBlocked = it }
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
        EditorToolbar(
            modifier = Modifier.fillMaxSize(),
            dockEdge = dockEdge,
            onDockEdgeChange = { dockEdge = it },
            toolKind = toolKind,
            onToolSelected = { kind ->
                toolKind = kind
                penView?.setTool(kind)
            },
            eraserMode = eraserMode,
            onEraserModeToggle = {
                eraserMode = if (eraserMode == EraserMode.WHOLE_STROKE) EraserMode.POINT else EraserMode.WHOLE_STROKE
                penView?.setEraserMode(eraserMode)
            },
            canUndo = canUndo,
            onUndo = { penView?.undo() },
            canRedo = canRedo,
            onRedo = { penView?.redo() },
            hasSelection = hasSelection,
            onDelete = { penView?.deleteSelection() },
            insertionActive = insertionState !is InsertionState.Idle,
            onInsertImage = {
                beginInsertion(ContentKind.IMAGE)
            },
            onInsertText = {
                beginInsertion(ContentKind.TEXT)
            },
            pageManagerOpen = pageManagerOpen,
            onPageManagerToggle = {
                pageManagerOpen = !pageManagerOpen
                notebookManagerOpen = false
                settingsOpen = false
            },
            onPageAdd = {
                penView?.let { pv ->
                    pv.addPage()
                    showNotice("已新增第 ${pv.currentPageIndex() + 1} 页")
                }
            },
            onPreviousPage = {
                if (penView?.navigatePage(-1) != true) showNotice("已经是第一页")
            },
            onNextPage = {
                if (penView?.navigatePage(1) != true) showNotice("已经是最后一页")
            },
            notebookManagerOpen = notebookManagerOpen,
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
            exportPanelOpen = exportPanelOpen,
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
            settingsOpen = settingsOpen,
            debugMode = debugMode,
            onSettingsOpen = {
                settingsOpen = true
                notebookManagerOpen = false
                pageManagerOpen = false
            },
            onDebugToggle = {
                debugMode = !debugMode
                showNotice(if (debugMode) "调试模式已开启" else "调试模式已关闭")
            },
            penToolbarSettings = penToolbarSettings,
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
            onInteractionBlockChange = { toolbarInteractionBlocked = it }
        )

        (insertionState as? InsertionState.ChoosingSource)?.let { choosing ->
            EinkModalOverlay(
                onDismissRequest = insertion::cancel,
                position = EinkModalPosition.TOP
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp)
                ) {
                    Text(
                        if (choosing.kind == ContentKind.IMAGE) "插入图片" else "插入文字",
                        fontSize = 24.sp
                    )
                    Button(
                        onClick = {
                            val missing = TransferPermissions.missingNotePermissions(context)
                            if (missing.isEmpty()) {
                                permissionRemoteAuto = false
                                snackbarScope.launch { insertion.remote(choosing.kind) }
                            } else {
                                permissionRemoteKind = choosing.kind
                                permissionRemoteAuto = false
                                transferPermissionLauncher.launch(missing)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        shape = RectangleShape
                    ) { Text(if (choosing.kind == ContentKind.IMAGE) "从手机取下一张" else "从手机取下一段") }
                    Button(
                        onClick = {
                            if (choosing.kind == ContentKind.IMAGE) {
                                insertion.cancel(); imagePicker.launch(arrayOf("image/*"))
                            } else {
                                insertion.manualText(); showNotice("请点击页面确定文字位置")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = RectangleShape
                    ) { Text(if (choosing.kind == ContentKind.IMAGE) "从系统文件选择" else "手动输入") }
                }
            }
        }

        (insertionState as? InsertionState.ChoosingClient)?.let { choosing ->
            EinkModalOverlay(onDismissRequest = insertion::cancel, position = EinkModalPosition.TOP) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                ) {
                    Text("选择 NoteLink", fontSize = 24.sp)
                    choosing.clients.forEach { available ->
                        val count = if (choosing.kind == ContentKind.IMAGE) {
                            available.imageCount
                        } else available.textCount
                        Button(
                            onClick = {
                                snackbarScope.launch {
                                    insertion.remote(available.client.id, choosing.kind)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RectangleShape
                        ) {
                            Text("${available.client.name} · $count 项")
                        }
                    }
                    EinkDialogAction("取消", onClick = insertion::cancel)
                }
            }
        }

        (insertionState as? InsertionState.WaitingForPhone)?.let { waiting ->
            EinkModalOverlay(onDismissRequest = insertion::cancel) {
                Column(Modifier.padding(20.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                    Text("正在查找 NoteLink", fontSize = 20.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(
                            "等待 NoteLink 发送${if (waiting.kind == ContentKind.IMAGE) "图片" else "文字"}",
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                        EinkDialogAction("取消", onClick = insertion::cancel)
                    }
                }
            }
        }

        (insertionState as? InsertionState.Error)?.let { error ->
            EinkModalOverlay(onDismissRequest = insertion::dismissError) {
                Column(Modifier.padding(20.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                    Text("NoteLink 传输", fontSize = 20.sp)
                    Text(error.message)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                        EinkDialogAction("知道了", onClick = insertion::dismissError)
                    }
                }
            }
        }

        val selectedRichObject = gen.let { penView?.selectedRichObject() }
        selectedRichObject?.let { selectedObject ->
            val objectCenterY =
                (selectedObject.pageBounds.top + selectedObject.pageBounds.bottom) / 2f
            val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
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
                            selectedObject.text
                        )
                    },
                    onDelete = { penView?.deleteSelection() },
                    onClose = { penView?.clearRichSelection() }
                )
                is ImageObject -> ImagePropertiesToolbar(
                    modifier = toolbarPosition,
                    onDelete = { penView?.deleteSelection() },
                    onClose = { penView?.clearRichSelection() }
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
                            .onFailure { showNotice("文字插入失败：${it.message}") }
                        is TextEditorRequest.Existing ->
                            penView?.updateTextObject(request.objectId, text = content)
                    }
                    textEditorRequest = null
                },
                onDismiss = {
                    if (request is TextEditorRequest.New) insertion.cancel()
                    textEditorRequest = null
                }
            )
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
                            showNotice(if (isBookmarked) "已添加书签" else "已取消书签")
                        }
                    }
                ),
            color = Color(0xDDF2F2F2),
            contentColor = Color.Black,
            tonalElevation = 3.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
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
                    .padding(start = 64.dp, top = 8.dp, bottom = 8.dp)
                    .width(thumbnailSize.width + 4.dp).fillMaxHeight()
                DockEdge.END -> Modifier.align(Alignment.CenterEnd)
                    .padding(end = 64.dp, top = 8.dp, bottom = 8.dp)
                    .width(thumbnailSize.width + 4.dp).fillMaxHeight()
                DockEdge.TOP -> Modifier.align(Alignment.TopCenter)
                    .padding(start = 8.dp, end = 8.dp, top = 64.dp)
                    .fillMaxWidth().height(thumbnailSize.height + PAGE_MANAGER_TAB_HEIGHT + 4.dp)
                DockEdge.BOTTOM -> Modifier.align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, bottom = 64.dp)
                    .fillMaxWidth().height(thumbnailSize.height + PAGE_MANAGER_TAB_HEIGHT + 4.dp)
            }
            PageManagerPanel(
                modifier = managerModifier,
                dockEdge = dockEdge,
                thumbnailSize = thumbnailSize,
                pages = pages,
                currentPageId = currentPageId,
                thumbnail = { id -> penView?.pageThumbnail(id) },
                requestThumbnails = { ids -> penView?.requestThumbnails(ids) },
                onSelect = { id ->
                    penView?.switchToPage(id)
                    pageManagerOpen = false
                },
                onAddAfter = { id ->
                    val newId = penView!!.addPageAfter(id, activate = true)
                    showNotice("已新增第 ${penView!!.currentPageIndex() + 1} 页")
                    newId
                },
                onDelete = { id ->
                    val pv = penView!!
                    if (!pv.canDeletePage()) {
                        showNotice("最后一页不能删除")
                        false
                    } else {
                        pv.deletePage(id)
                    }
                },
                onMove = { id, target -> penView!!.movePage(id, target) },
                onNotice = showNotice
            )
        }

        val notebookSummaries = if (notebookManagerOpen && penView != null) {
            gen.let { runCatching { penView!!.notebookSummaries() }.getOrElse { emptyList() } }
        } else {
            emptyList()
        }
        if (notebookManagerOpen && penView != null) {
            NotebookManagerScreen(
                summaries = notebookSummaries,
                pages = pages,
                currentToEndIds = penView!!.currentPageToEndIds(),
                busy = notebookBusy,
                notebookThumbnailSize = notebookThumbnailSize,
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
                                showNotice("已切换笔记本")
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
                onDeleteNotebook = { id ->
                    notebookBusy = true
                    penView!!.deleteNotebook(id) { result ->
                        notebookBusy = false
                        if (result.isSuccess) showNotice("已删除笔记本")
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
                onClose = { if (!notebookBusy) notebookManagerOpen = false }
            )
        }

        if (settingsOpen) {
            SettingsScreen(
                debugMode = debugMode,
                startupBehavior = startupBehavior,
                pairedClients = pairedClients,
                onlineClients = onlineNoteLinks,
                pairingCandidates = pairingCandidates,
                pairingScanActive = pairingScanActive,
                transferStatus = transferStatus,
                transferPermissionsGranted = missingTransferPermissions.isEmpty(),
                skipSourceSelectionWhenQueueAvailable = skipSourceSelectionWhenQueueAvailable,
                autoCreatePageOnNextAtEnd = autoCreatePageOnNextAtEnd,
                onDebugModeChange = { debugMode = it },
                onStartupBehaviorChange = { behavior ->
                    runCatching { penView?.setStartupBehavior(behavior) }
                        .onSuccess {
                            startupBehavior = behavior
                            showNotice("启动行为已保存")
                        }
                        .onFailure { showNotice("保存设置失败：${it.message}") }
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
                onScanClients = {
                    if (missingTransferPermissions.isNotEmpty()) {
                        showNotice("请先授予附近设备权限")
                    } else if (!pairingScanActive) {
                        pairingScanActive = true
                        snackbarScope.launch {
                            runCatching { phoneTransfer.discoverPairingCandidates() }
                                .onSuccess { pairingCandidates = it }
                                .onFailure { showNotice("扫描失败：${it.message}") }
                            onlineNoteLinks = runCatching {
                                phoneTransfer.discoverAvailable(timeoutMillis = 2_000L) { onlineNoteLinks = it }
                            }.getOrDefault(emptyList())
                            pairingScanActive = false
                        }
                    }
                },
                onPairClient = { candidate, code ->
                    snackbarScope.launch {
                        pairingScanActive = true
                        runCatching { phoneTransfer.pair(candidate, code) }
                            .onSuccess {
                                pairedClients = phoneTransfer.pairing.pairedClients
                                pairingCandidates = pairingCandidates.filterNot { value -> value.deviceId == it.id }
                                onlineNoteLinks = phoneTransfer.discoverAvailable(timeoutMillis = 2_000L)
                                transferStatusRevision++
                                showNotice("${it.name} 配对完成")
                            }
                            .onFailure { showNotice("配对失败：${it.message}") }
                        pairingScanActive = false
                    }
                },
                onRenameClient = { id, name ->
                    runCatching { phoneTransfer.pairing.rename(id, name) }
                        .onSuccess {
                            pairedClients = phoneTransfer.pairing.pairedClients
                            showNotice("名称已保存")
                        }
                        .onFailure { showNotice("重命名失败：${it.message}") }
                },
                onUnpairClient = { id ->
                    phoneTransfer.pairing.unpair(id)
                    pairedClients = phoneTransfer.pairing.pairedClients
                    transferStatusRevision++
                    showNotice("已移除 NoteLink 配对")
                },
                onTransferPermissions = {
                    permissionRemoteKind = null
                    permissionRemoteAuto = false
                    transferPermissionLauncher.launch(TransferPermissions.missingNotePermissions(context))
                },
                onRefreshTransferStatus = {
                    transferStatusRevision++
                    showNotice("连接状态已刷新")
                },
                onClose = { settingsOpen = false }
            )
        }

        if (exportPanelOpen && penView != null) {
            val pv = penView!!
            val currentNotebookId = pv.currentNotebookId()
            ExportManagerScreen(
                viewModel = exportViewModel,
                initialNotebookId = exportInitialNotebookId ?: currentNotebookId,
                creationRequest = exportCreationRequest,
                currentNotebookId = currentNotebookId,
                pairedClients = if (missingTransferPermissions.isEmpty()) {
                    val onlineIds = onlineNoteLinks.map { it.client.id }.toSet()
                    pairedClients.filter { it.id in onlineIds }
                } else emptyList(),
                pageBitmap = { id -> pv.pageThumbnail(id) },
                requestThumbnails = { ids -> pv.requestThumbnails(ids) },
                beforeExport = { notebookId ->
                    if (notebookId == pv.currentNotebookId()) {
                        pv.flushPersistenceForExport()
                    } else true
                },
                sendToNoteLink = { clientId, artifact, progress ->
                    phoneTransfer.sendExport(clientId, artifact, progress)
                },
                onNotice = showNotice,
                onClose = { exportPanelOpen = false }
            )
        }

        pendingNotebookCreation?.let { request ->
            NotebookNameDialog(
                onConfirm = { title ->
                    pendingNotebookCreation = null
                    val pv = penView ?: return@NotebookNameDialog
                    notebookBusy = true
                    val complete: (Result<UUID>) -> Unit = { result ->
                        notebookBusy = false
                        if (result.isSuccess) {
                            notebookManagerOpen = false
                            showNotice("已创建笔记本“$title”")
                        }
                    }
                    when (request) {
                        NotebookCreationRequest.Blank -> pv.createBlankNotebook(title, complete)
                        is NotebookCreationRequest.Transfer ->
                            pv.transferPagesToNewNotebook(request.pageIds, title, complete)
                    }
                },
                onDismiss = { if (!notebookBusy) pendingNotebookCreation = null }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                .penInputGuard { snackbarInteractionBlocked = it }
        )
    }
}

private fun noteTransferStatus(context: Context, isPaired: Boolean, permissionsGranted: Boolean): String {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return "设备不支持 BLE"
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return "设备不支持 Wi-Fi Direct"
    if (!permissionsGranted) return "需要附近设备权限"
    if (context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) return "蓝牙已关闭"
    if (context.getSystemService(WifiManager::class.java)?.isWifiEnabled != true) return "WLAN 已关闭"
    return if (isPaired) "已就绪，插入时按需连接手机" else "请先与手机配对"
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
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier.border(2.dp, Color(0xFF4E5F70), RectangleShape),
        shape = RectangleShape,
        color = Color(0xFFF2F2F2),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字体", modifier = Modifier.padding(horizontal = 6.dp))
                TextFontFamily.entries.forEach { family ->
                    TextButton(onClick = { onFont(family) }) {
                        Text(
                            when (family) {
                                TextFontFamily.SANS_SERIF -> "无衬线"
                                TextFontFamily.SERIF -> "衬线"
                                TextFontFamily.MONOSPACE -> "等宽"
                            },
                            color = if (family == text.fontFamily) Color.Black else Color.Gray
                        )
                    }
                }
                TextButton(onClick = onEdit) { Text("编辑文字") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除文字")
                }
                TextButton(onClick = onClose) { Text("完成") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字号", modifier = Modifier.padding(horizontal = 6.dp))
                listOf(12f, 16f, 20f, 24f, 32f, 48f).forEach { size ->
                    TextButton(onClick = { onSize(size) }) {
                        Text(
                            size.toInt().toString(),
                            color = if (size == text.fontSize) Color.Black else Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePropertiesToolbar(
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier.border(2.dp, Color(0xFF4E5F70), RectangleShape),
        shape = RectangleShape,
        color = Color(0xFFF2F2F2),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除图片")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Check, contentDescription = "完成编辑图片")
            }
        }
    }
}

@Composable
private fun TextEditorDialog(
    request: TextEditorRequest,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(request) { mutableStateOf(request.initialText) }
    val focusRequester = remember(request) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(request) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    EinkModalOverlay(onDismissRequest = onDismiss, position = EinkModalPosition.TOP) {
        Column(Modifier.padding(20.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            Text(if (request is TextEditorRequest.New) "插入文字" else "编辑文字", fontSize = 20.sp)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                label = { Text("文字内容") },
                minLines = 3,
                maxLines = 8,
                shape = RectangleShape
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                EinkDialogAction("取消", onClick = onDismiss)
                EinkDialogAction("确定", enabled = value.isNotBlank()) {
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

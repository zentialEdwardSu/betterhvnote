package com.betterhv.note

import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private var penView: PenDrawView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.log("MainActivity", "onCreate")
        NativeSelfTest.run()
        setContent {
            AppRoot(onView = { penView = it })
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        PenButtonTracker.observeMotion(event, "touch")
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        PenButtonTracker.observeMotion(event, "generic")
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        PenButtonTracker.observeKey(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        penView?.teardown()
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(onView: (PenDrawView) -> Unit) {
    val context = LocalContext.current
    val penSettingsStore = remember(context) { PenSettingsStore(context) }
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

        AndroidView(
            factory = { ctx ->
                PenDrawView(ctx).also {
                    it.setPenStyle(activePenStyle)
                    penView = it
                    onView(it)
                    it.setOnDocChanged { docVersion++ }
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
        }

        // Debug-only affordances: the event-log overlay and the Flush/Clear
        // buttons used to exercise materialize()/clear() without a real
        // erase or page-turn gesture. Hidden by default so the toolbar's
        // Menu > Debug mode toggle controls whether they show.
        if (debugMode) {
            LogOverlay(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).width(360.dp)
            )
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
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
                penView?.setUiInputBlocked(visible)
            },
            debugMode = debugMode,
            onDebugModeChange = { debugMode = it }
        )
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

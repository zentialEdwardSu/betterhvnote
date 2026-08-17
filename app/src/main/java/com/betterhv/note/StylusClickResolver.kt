package com.betterhv.note

import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInteropFilter

/** Per-control bridge between pointer DOWN classification and Compose onClick. */
internal class StylusClickResolver {
    private var armed = PenSideButton.NONE

    fun observe(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        val effective = event.buttonState or PenButtonTracker.currentButtonState
        armed = PenFunctionKey.classifyClickModifier(toolType, effective, event.source)
    }

    fun consume(): PenSideButton {
        val tracked = PenButtonTracker.consumeClickModifier()
        val result = if (armed != PenSideButton.NONE) armed else tracked
        armed = PenSideButton.NONE
        return result
    }

    fun clear() {
        armed = PenSideButton.NONE
    }
}

/**
 * Consumes only stylus-modified clicks. A normal pen DOWN returns false, so the
 * writing surface below keeps receiving the complete gesture.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.stylusModifiedClickable(
    onSide1Click: (() -> Unit)? = null,
    onSide2Click: (() -> Unit)? = null,
    onSide3Click: (() -> Unit)? = null
): Modifier = composed {
    var armed by remember { mutableStateOf(PenSideButton.NONE) }
    @Suppress("DEPRECATION")
    pointerInteropFilter { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val toolType = if (event.pointerCount > 0) {
                    event.getToolType(0)
                } else {
                    MotionEvent.TOOL_TYPE_UNKNOWN
                }
                val modifier = PenFunctionKey.classifyClickModifier(
                    toolType,
                    event.buttonState or PenButtonTracker.currentButtonState,
                    event.source
                )
                armed = when (modifier) {
                    PenSideButton.SIDE_1 -> modifier.takeIf { onSide1Click != null }
                    PenSideButton.SIDE_2 -> modifier.takeIf { onSide2Click != null }
                    PenSideButton.SIDE_3 -> modifier.takeIf { onSide3Click != null }
                    PenSideButton.NONE -> null
                } ?: PenSideButton.NONE
                armed != PenSideButton.NONE
            }
            MotionEvent.ACTION_UP -> {
                val action = armed
                if (action == PenSideButton.NONE) return@pointerInteropFilter false
                armed = PenSideButton.NONE
                PenButtonTracker.consumeClickModifier()
                when (action) {
                    PenSideButton.SIDE_1 -> onSide1Click?.invoke()
                    PenSideButton.SIDE_2 -> onSide2Click?.invoke()
                    PenSideButton.SIDE_3 -> onSide3Click?.invoke()
                    PenSideButton.NONE -> Unit
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = armed != PenSideButton.NONE
                armed = PenSideButton.NONE
                if (consumed) PenButtonTracker.consumeClickModifier()
                consumed
            }
            else -> armed != PenSideButton.NONE
        }
    }
}

/** Suspend ROM ink before a stylus can touch an interactive surface. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.penInputGuard(onBlockedChange: (Boolean) -> Unit): Modifier = composed {
    var blocked by remember { mutableStateOf(false) }
    var stylusHovering by remember { mutableStateOf(false) }
    val currentCallback by rememberUpdatedState(onBlockedChange)

    fun updateBlocked(value: Boolean) {
        if (blocked == value) return
        blocked = value
        currentCallback(value)
    }

    DisposableEffect(Unit) {
        onDispose {
            if (blocked) currentCallback(false)
        }
    }

    @Suppress("DEPRECATION")
    pointerInteropFilter { event ->
        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        val stylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> if (stylus) {
                stylusHovering = true
                updateBlocked(true)
            }
            MotionEvent.ACTION_DOWN -> updateBlocked(true)
            MotionEvent.ACTION_UP -> if (!stylusHovering) updateBlocked(false)
            MotionEvent.ACTION_HOVER_EXIT -> {
                stylusHovering = false
                updateBlocked(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                stylusHovering = false
                updateBlocked(false)
            }
        }
        false
    }
}

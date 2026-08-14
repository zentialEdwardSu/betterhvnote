package com.betterhv.note.doc

/** Spec §41-43: every document mutation goes through a Command so it can be undone. */
interface Command {
    fun execute()
    fun undo()
}

/**
 * Stack-based undo/redo (spec §41, §44). A new command clears the redo stack --
 * redoing after a fresh edit would silently discard that edit's inverse.
 *
 * Command merge (§44) is deliberately not implemented as a generic post-hoc
 * pass here: tools that need "one undo per gesture" (e.g. drag-to-move) build
 * one command incrementally while the gesture is in progress and only call
 * [execute] once, at gesture end -- see SelectionTool.
 */
class CommandStack {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun execute(command: Command) {
        command.execute()
        undoStack.addLast(command)
        redoStack.clear()
    }

    fun undo() {
        val command = undoStack.removeLastOrNull() ?: return
        command.undo()
        redoStack.addLast(command)
    }

    fun redo() {
        val command = redoStack.removeLastOrNull() ?: return
        command.execute()
        undoStack.addLast(command)
    }

    /**
     * Records a [command] that the caller has ALREADY executed (used by
     * gesture-driven tools that mutate the document live, point by point, for
     * immediate visual feedback, and only need one undo step recorded once the
     * gesture ends -- see CompositeCommand).
     */
    fun push(command: Command) {
        undoStack.addLast(command)
        redoStack.clear()
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

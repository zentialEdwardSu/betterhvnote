package com.betterhv.note.doc

import java.util.UUID

/** Spec §41-43: every document mutation goes through a Command so it can be undone. */
interface Command {
    fun execute()
    fun undo()

    /** Object ids that must be reconciled after execute/undo/redo. */
    val affectedObjects: Map<UUID, Set<UUID>> get() = emptyMap()

    /** Resolves post-command state even if the page Scene was evicted from Notebook's page cache. */
    fun currentObject(pageId: UUID, objectId: UUID): PageObject? = null

    /** Resolves the Page held by undo history even after Notebook detached it from the Scene cache. */
    fun currentPage(pageId: UUID): Page? = null

    /** Page ids whose creation/deletion/order metadata changed. */
    val affectedPages: Set<UUID> get() = emptySet()

    val changesPageStructure: Boolean get() = false
}

enum class CommandAction { EXECUTE, UNDO, REDO }

/**
 * Stack-based undo/redo (spec §41, §44). A new command clears the redo stack --
 * redoing after a fresh edit would silently discard that edit's inverse.
 *
 * Command merge (§44) is deliberately not implemented as a generic post-hoc
 * pass here: tools that need "one undo per gesture" (e.g. drag-to-move) build
 * one command incrementally while the gesture is in progress and only call
 * [execute] once, at gesture end -- see SelectionTool.
 */
class CommandStack(
    private var onCommitted: ((Command, CommandAction) -> Unit)? = null
) {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun execute(command: Command) {
        command.execute()
        undoStack.addLast(command)
        redoStack.clear()
        onCommitted?.invoke(command, CommandAction.EXECUTE)
    }

    fun undo() {
        val command = undoStack.removeLastOrNull() ?: return
        command.undo()
        redoStack.addLast(command)
        onCommitted?.invoke(command, CommandAction.UNDO)
    }

    fun redo() {
        val command = redoStack.removeLastOrNull() ?: return
        command.execute()
        undoStack.addLast(command)
        onCommitted?.invoke(command, CommandAction.REDO)
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
        onCommitted?.invoke(command, CommandAction.EXECUTE)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    fun setOnCommitted(listener: ((Command, CommandAction) -> Unit)?) {
        onCommitted = listener
    }
}

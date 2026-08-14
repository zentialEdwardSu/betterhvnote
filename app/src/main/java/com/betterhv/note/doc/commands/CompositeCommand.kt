package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command

/**
 * Bundles Commands that were each already executed live (one per hit, as a
 * gesture like an eraser drag crossed each object) into a single undo/redo
 * step, so one undo reverts the whole gesture rather than one entry per
 * object touched (spec §44's "one undo per drag" intent). Pushed via
 * [com.betterhv.note.doc.CommandStack.push] since the operations already ran.
 */
class CompositeCommand(private val operations: List<Command>) : Command {
    override fun execute() {
        for (op in operations) op.execute()
    }

    override fun undo() {
        for (op in operations.asReversed()) op.undo()
    }
}

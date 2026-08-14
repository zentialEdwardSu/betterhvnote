package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.Transform2D
import java.util.UUID

/**
 * Move/Scale (spec §38-39). [after] is mutable while a drag is in progress --
 * the tool calls [updateAfter] and re-applies on every batch for live feedback
 * -- and is sealed once the gesture ends and this is pushed to the
 * [com.betterhv.note.doc.CommandStack]. This is what gives "one undo per
 * drag" (spec §44's command-merge intent) without a separate merge pass.
 */
class TransformObjectsCommand(
    private val page: Page,
    private val ids: List<UUID>,
    private val before: Map<UUID, Transform2D>,
    private var after: Map<UUID, Transform2D>
) : Command {

    fun updateAfter(newAfter: Map<UUID, Transform2D>) {
        after = newAfter
    }

    /** Applies the current [after] transforms immediately, for live drag feedback. */
    fun applyLive() {
        for (id in ids) after[id]?.let { page.updateObjectTransform(id, it) }
    }

    override fun execute() {
        for (id in ids) after[id]?.let { page.updateObjectTransform(id, it) }
    }

    override fun undo() {
        for (id in ids) before[id]?.let { page.updateObjectTransform(id, it) }
    }
}

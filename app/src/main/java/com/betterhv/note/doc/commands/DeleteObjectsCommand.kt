package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject
import java.util.UUID

/** Spec §40: snapshots the removed objects so undo can restore them exactly. */
class DeleteObjectsCommand(
    private val page: Page,
    private val ids: List<UUID>
) : Command {
    private var snapshots: List<PageObject> = emptyList()

    override fun execute() {
        snapshots = ids.mapNotNull { page.removeObject(it) }
    }

    override fun undo() {
        for (obj in snapshots) page.addObject(obj)
    }
}

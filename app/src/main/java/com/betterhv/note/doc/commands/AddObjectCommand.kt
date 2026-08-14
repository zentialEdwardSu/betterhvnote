package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject

class AddObjectCommand(
    private val page: Page,
    private val obj: PageObject
) : Command {
    override fun execute() {
        page.addObject(obj)
    }

    override fun undo() {
        page.removeObject(obj.id)
    }
}

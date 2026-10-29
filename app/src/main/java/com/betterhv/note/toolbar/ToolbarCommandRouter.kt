package com.betterhv.note.toolbar

import com.betterhv.note.CommandBinding

/** Resolves a command id to the toolbar action that owns it, if any. */
class ToolbarCommandRouter {
  fun action(binding: CommandBinding): ToolbarActionSpec? =
    ToolbarCatalog.actionFor(binding.commandId)
}

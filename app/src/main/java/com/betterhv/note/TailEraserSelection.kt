package com.betterhv.note

import com.betterhv.note.doc.SelectionSet

/** Decide once at DOWN, in page coordinates; subsequent samples cannot trigger another deletion. */
internal fun tailErasesSelection(selection: SelectionSet, x: Float, y: Float): Boolean =
  !selection.isEmpty && x in selection.bounds.left..selection.bounds.right &&
    y in selection.bounds.top..selection.bounds.bottom

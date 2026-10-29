package com.betterhv.note.export

import java.security.MessageDigest

/**
 * Single source of truth for reusable page files.
 *
 * The key deliberately excludes the task id: the same page content rendered with
 * the same background, renderer and mode can be shared by a single-page task, a
 * selection task and an all-pages task. Mode is part of the key so a fast-mode
 * raster page is never consumed by a vector-mode task (or vice versa).
 */
object ExportPageCacheKey {
  fun fileName(source: ExportPageSource, mode: ExportRenderMode, rendererVersion: Int): String =
    "${source.id}-r${source.contentRevision}-b${backgroundHash(source.backgroundRevision)}" +
      "-v$rendererVersion-${mode.name.lowercase()}.pdf"

  private fun backgroundHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
      .take(6)
      .joinToString("") { "%02x".format(it) }
}

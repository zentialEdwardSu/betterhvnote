package com.betterhv.note.notelink

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.betterhv.transfer.core.ContentKind

sealed interface ClipboardContent {
  data class Image(val uri: Uri) : ClipboardContent
  data class Text(val text: String) : ClipboardContent
}

/** Read only on an explicit paste request while the application has focus. */
object ClipboardSource {
  fun read(context: Context, kind: ContentKind): ClipboardContent {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: error("剪贴板为空或暂时不可访问")
    for (index in 0 until clip.itemCount) {
      val item = clip.getItemAt(index)
      if (kind == ContentKind.TEXT) {
        item.text?.toString()?.takeIf(String::isNotBlank)?.let { return ClipboardContent.Text(it) }
      } else if (kind == ContentKind.IMAGE) {
        val uri = item.uri ?: continue
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime?.startsWith("image/") == true || clip.description.hasMimeType("image/*") ||
          (mime == null && clip.itemCount == 1 && uri.scheme in setOf("content", "file"))) {
          // ImageAssetStore validates the decoded image; vendor providers sometimes omit MIME metadata.
          return ClipboardContent.Image(uri)
        }
      }
    }
    error(if (kind == ContentKind.IMAGE) "剪贴板中没有可用图片" else "剪贴板中没有可用文字")
  }
}

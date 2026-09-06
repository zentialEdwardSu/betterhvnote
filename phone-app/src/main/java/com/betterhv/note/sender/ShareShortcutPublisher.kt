package com.betterhv.note.sender

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.betterhv.note.sender.shared.noteLinkText
import com.betterhv.transfer.core.PairedDevice

object ShareShortcutPublisher {
  private const val ID = "send-to-paired-note"
  private const val CATEGORY = "com.betterhv.note.sender.category.SEND_TO_NOTE"

  fun update(context: Context, device: PairedDevice?) {
    if (device == null) {
      ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(ID))
      return
    }
    val shortcut = ShortcutInfoCompat.Builder(context, ID)
      .setShortLabel(noteLinkText("发送到 ${device.name}", "Send to ${device.name}"))
      .setLongLabel(noteLinkText("发送到 BetterHv Note：${device.name}", "Send to BetterHv Note: ${device.name}"))
      .setIcon(IconCompat.createWithResource(context, android.R.drawable.stat_sys_upload_done))
      .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/*"))
      .setCategories(setOf(CATEGORY))
      .setLongLived(true)
      .build()
    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
  }
}

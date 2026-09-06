package com.betterhv.note

import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/** Normalizes the raw and ROM-remapped N10 Pro body-key families. */
object N10ProHardwareKeys {
  private const val TAG = "HardwareKey"
  private const val ACTION_SET_KEY_SCENE = "hanvon.intent.setcurcustomkeytype"
  private const val EXTRA_KEY_SCENE = "hanvon_setcurkeytype"
  private const val NOTE_KEY_SCENE = 1

  private val semanticKeys = mapOf(
    519 to HardwareKeyId.K1,
    522 to HardwareKeyId.K2,
    525 to HardwareKeyId.K3,
    516 to HardwareKeyId.K4,
    524 to HardwareKeyId.K5,
    526 to HardwareKeyId.K6,
    527 to HardwareKeyId.K7,
    531 to HardwareKeyId.K8,
  )

  fun enterNoteKeyScene(context: Context) {
    context.sendBroadcast(
      Intent(ACTION_SET_KEY_SCENE).putExtra(EXTRA_KEY_SCENE, NOTE_KEY_SCENE),
    )
    EventLog.log(TAG, "requested note key scene")
  }

  fun dispatch(event: KeyEvent, onPress: (HardwareKeyId) -> Unit = {}): Boolean {
    val key = keyId(event.keyCode) ?: return false
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
      EventLog.log(
        TAG,
        "pressed key=K${key.number} keyCode=${event.keyCode} scanCode=${event.scanCode}",
      )
      onPress(key)
    }
    return true
  }

  internal fun keyId(keyCode: Int): HardwareKeyId? = when (keyCode) {
    in 489..496 -> HardwareKeyId.entries[keyCode - 489]
    else -> semanticKeys[keyCode]
  }

  internal fun keyName(keyCode: Int): String? = keyId(keyCode)?.let { "K${it.number}" }
}

package com.betterhv.note.sender.data

import android.content.Context
import androidx.core.content.edit
import com.betterhv.transfer.core.SendOrder
import com.betterhv.transfer.core.i18n.NoteLinkI18n
import com.betterhv.transfer.core.i18n.NoteLinkLanguage

class NoteLinkSettings(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  init {
    NoteLinkI18n.language = language
  }

  var displayName: String
    get() = preferences.getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME
    set(value) {
      preferences.edit { putString(KEY_DISPLAY_NAME, validateDisplayName(value)) }
    }

  var showRecentTransferEvents: Boolean
    get() = preferences.getBoolean(KEY_SHOW_RECENT_TRANSFER_EVENTS, true)
    set(value) = preferences.edit { putBoolean(KEY_SHOW_RECENT_TRANSFER_EVENTS, value) }

  var sendOrder: SendOrder
    get() = SendOrder.fromStorageId(preferences.getString(KEY_SEND_ORDER, null))
    set(value) {
      preferences.edit { putString(KEY_SEND_ORDER, value.name) }
    }

  var language: NoteLinkLanguage
    get() = NoteLinkLanguage.fromStorageId(preferences.getString(KEY_LANGUAGE, null))
    set(value) {
      preferences.edit { putString(KEY_LANGUAGE, value.storageId) }
      NoteLinkI18n.language = value
    }

  companion object {
    const val DEFAULT_DISPLAY_NAME = "NoteLink"
    const val MAX_DISPLAY_NAME_BYTES = 18
    private const val PREFERENCES = "notelink_settings"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_SHOW_RECENT_TRANSFER_EVENTS = "show_recent_transfer_events"
    private const val KEY_SEND_ORDER = "send_order"
    private const val KEY_LANGUAGE = "language"

    fun validateDisplayName(value: String): String {
      val normalized = value.trim()
      require(normalized.isNotEmpty()) { "Display name cannot be empty" }
      require(
        normalized.none(Char::isISOControl),
      ) { "Display name cannot contain control characters" }
      require(normalized.encodeToByteArray().size <= MAX_DISPLAY_NAME_BYTES) {
        "Display name cannot exceed $MAX_DISPLAY_NAME_BYTES UTF-8 bytes"
      }
      return normalized
    }
  }
}

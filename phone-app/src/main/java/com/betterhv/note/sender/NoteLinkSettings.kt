package com.betterhv.note.sender

import android.content.Context
import androidx.core.content.edit
import com.betterhv.note.sender.shared.NoteLinkI18n
import com.betterhv.note.sender.shared.NoteLinkLanguage
import com.betterhv.note.sender.shared.noteLinkText

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
        private const val KEY_LANGUAGE = "language"

        fun validateDisplayName(value: String): String {
            val normalized = value.trim()
            require(normalized.isNotEmpty()) { noteLinkText("显示名称不能为空", "Display name cannot be empty") }
            require(
                normalized.none(Char::isISOControl)
            ) { noteLinkText("显示名称不能包含控制字符", "Display name cannot contain control characters") }
            require(normalized.encodeToByteArray().size <= MAX_DISPLAY_NAME_BYTES) {
                noteLinkText(
                    "显示名称最多 $MAX_DISPLAY_NAME_BYTES 个英文字符或 6 个汉字",
                    "Display name cannot exceed $MAX_DISPLAY_NAME_BYTES UTF-8 bytes",
                )
            }
            return normalized
        }
    }
}

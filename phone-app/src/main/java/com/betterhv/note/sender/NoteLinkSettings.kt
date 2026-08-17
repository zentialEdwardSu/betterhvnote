package com.betterhv.note.sender

import android.content.Context

class NoteLinkSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var displayName: String
        get() = preferences.getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME
        set(value) {
            preferences.edit().putString(KEY_DISPLAY_NAME, validateDisplayName(value)).apply()
        }

    companion object {
        const val DEFAULT_DISPLAY_NAME = "NoteLink"
        const val MAX_DISPLAY_NAME_BYTES = 18
        private const val PREFERENCES = "notelink_settings"
        private const val KEY_DISPLAY_NAME = "display_name"

        fun validateDisplayName(value: String): String {
            val normalized = value.trim()
            require(normalized.isNotEmpty()) { "显示名称不能为空" }
            require(normalized.none(Char::isISOControl)) { "显示名称不能包含控制字符" }
            require(normalized.encodeToByteArray().size <= MAX_DISPLAY_NAME_BYTES) {
                "显示名称最多 $MAX_DISPLAY_NAME_BYTES 个英文字符或 6 个汉字"
            }
            return normalized
        }
    }
}

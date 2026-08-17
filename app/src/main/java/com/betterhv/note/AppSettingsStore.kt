package com.betterhv.note

import android.content.Context

/** Small, version-tolerant store for user-facing editor behavior. */
class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var skipSourceSelectionWhenQueueAvailable: Boolean
        get() = preferences.getBoolean(KEY_SKIP_SOURCE_SELECTION, false)
        set(value) = preferences.edit().putBoolean(KEY_SKIP_SOURCE_SELECTION, value).apply()

    var autoCreatePageOnNextAtEnd: Boolean
        get() = preferences.getBoolean(KEY_AUTO_CREATE_PAGE, false)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_CREATE_PAGE, value).apply()

    companion object {
        private const val NAME = "app_settings"
        private const val KEY_SKIP_SOURCE_SELECTION = "skip_source_selection_when_queue_available"
        private const val KEY_AUTO_CREATE_PAGE = "auto_create_page_on_next_at_end"
    }
}

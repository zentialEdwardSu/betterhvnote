package com.betterhv.note

import android.content.Context

internal fun decodeVisibleToolbarItems(stored: Set<String>): Set<ToolbarItem> {
    val migrated = stored.mapNotNull(ToolbarItem::fromStorageId)
        .filterNot { it == ToolbarItem.MENU }
        .toMutableSet()
    if ("linked_note" in stored) migrated += ToolbarItem.LASSO
    return migrated
}

/** Small, version-tolerant store for user-facing editor behavior. */
class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var skipSourceSelectionWhenQueueAvailable: Boolean
        get() = preferences.getBoolean(KEY_SKIP_SOURCE_SELECTION, false)
        set(value) = preferences.edit().putBoolean(KEY_SKIP_SOURCE_SELECTION, value).apply()

    var autoCreatePageOnNextAtEnd: Boolean
        get() = preferences.getBoolean(KEY_AUTO_CREATE_PAGE, false)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_CREATE_PAGE, value).apply()

    var showRecentTransferEvents: Boolean
        get() = preferences.getBoolean(KEY_SHOW_RECENT_TRANSFER_EVENTS, true)
        set(value) = preferences.edit().putBoolean(KEY_SHOW_RECENT_TRANSFER_EVENTS, value).apply()

    var visibleToolbarItems: Set<ToolbarItem>
        get() {
            if (!preferences.contains(KEY_TOOLBAR_ITEMS)) return ToolbarItem.defaults
            val stored = preferences.getStringSet(KEY_TOOLBAR_ITEMS, emptySet()).orEmpty()
            // v1 exposed linked-note as a standalone tool. It is now Side1 of
            // the lasso tool; keep it reachable for users who had hidden lasso
            // but explicitly left the old linked-note button visible.
            return decodeVisibleToolbarItems(stored)
        }
        set(value) = preferences.edit()
            .putStringSet(
                KEY_TOOLBAR_ITEMS,
                value.filterNot { it == ToolbarItem.MENU }.mapTo(mutableSetOf()) { it.storageId }
            )
            .apply()

    var toolbarHidden: Boolean
        get() = preferences.getBoolean(KEY_TOOLBAR_HIDDEN, false)
        set(value) = preferences.edit().putBoolean(KEY_TOOLBAR_HIDDEN, value).apply()

    var toolbarDockEdge: DockEdge
        get() = runCatching {
            DockEdge.valueOf(preferences.getString(KEY_TOOLBAR_DOCK_EDGE, null).orEmpty())
        }.getOrDefault(DockEdge.START)
        set(value) = preferences.edit().putString(KEY_TOOLBAR_DOCK_EDGE, value.name).apply()

    var toolbarDockFraction: Float
        get() = preferences.getFloat(KEY_TOOLBAR_DOCK_FRACTION, 0.5f).coerceIn(0f, 1f)
        set(value) = preferences.edit().putFloat(KEY_TOOLBAR_DOCK_FRACTION, value.coerceIn(0f, 1f)).apply()

    var templateDirectoryUri: String?
        get() = preferences.getString(KEY_TEMPLATE_DIRECTORY_URI, null)
        set(value) = preferences.edit().apply {
            if (value == null) remove(KEY_TEMPLATE_DIRECTORY_URI) else putString(KEY_TEMPLATE_DIRECTORY_URI, value)
        }.apply()

    fun loadHardwareShortcuts(): HardwareShortcutBindings {
        var result = HardwareShortcutBindings()
        ShortcutScene.entries.forEach { scene ->
            HardwareKeyId.entries.forEach { key ->
                val id = preferences.getString(shortcutKey(scene, key), null) ?: return@forEach
                val action = ShortcutAction.fromStorageId(id)?.takeIf { it.scene == scene } ?: return@forEach
                result = result.bind(scene, key, action)
            }
        }
        return result
    }

    fun saveHardwareShortcut(scene: ShortcutScene, key: HardwareKeyId, action: ShortcutAction?) {
        val editor = preferences.edit()
        if (action == null) editor.remove(shortcutKey(scene, key))
        else {
            require(action.scene == scene)
            editor.putString(shortcutKey(scene, key), action.storageId)
        }
        editor.apply()
    }

    fun clearHardwareShortcuts(scene: ShortcutScene) {
        preferences.edit().also { editor ->
            HardwareKeyId.entries.forEach { editor.remove(shortcutKey(scene, it)) }
        }.apply()
    }

    private fun shortcutKey(scene: ShortcutScene, key: HardwareKeyId): String =
        "shortcut_${scene.storageId}_${key.number}"

    companion object {
        private const val NAME = "app_settings"
        private const val KEY_SKIP_SOURCE_SELECTION = "skip_source_selection_when_queue_available"
        private const val KEY_AUTO_CREATE_PAGE = "auto_create_page_on_next_at_end"
        private const val KEY_SHOW_RECENT_TRANSFER_EVENTS = "show_recent_transfer_events"
        private const val KEY_TOOLBAR_ITEMS = "toolbar_visible_items_v1"
        private const val KEY_TOOLBAR_HIDDEN = "toolbar_hidden"
        private const val KEY_TOOLBAR_DOCK_EDGE = "toolbar_dock_edge"
        private const val KEY_TOOLBAR_DOCK_FRACTION = "toolbar_dock_fraction"
        private const val KEY_TEMPLATE_DIRECTORY_URI = "template_directory_uri"
    }
}

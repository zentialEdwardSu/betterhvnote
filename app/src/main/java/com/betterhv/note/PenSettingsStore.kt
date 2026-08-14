package com.betterhv.note

import android.content.Context

/** Small synchronous preference store; writes use apply() because pen changes must not block UI. */
class PenSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): PenToolbarSettings {
        val hasNewSlots = preferences.contains(slotKey(0, KEY_ACTIVE_TYPE))
        val slots = List(PenToolbarSettings.SLOT_COUNT) { slot ->
            if (slot == 0 && !hasNewSlots) loadLegacySettings() else loadSlot(slot)
        }
        val activeSlot = preferences.getInt(KEY_ACTIVE_SLOT, 0)
            .takeIf { it in 0 until PenToolbarSettings.SLOT_COUNT } ?: 0
        return PenToolbarSettings(activeSlot = activeSlot, slots = slots)
    }

    /** Migrates the former single-pen preference keys into toolbar slot zero. */
    private fun loadLegacySettings(): PenSettings {
        val colors = mutableMapOf<String, Int>()
        val widths = mutableMapOf<String, Int>()
        for (type in com.betterhv.note.ink.PenType.entries) {
            val id = PenProfiles.stableId(type)
            if (preferences.contains("color_$id")) {
                colors[id] = preferences.getInt("color_$id", PenPreset.DEFAULT_COLOR_INDEX)
            }
            if (preferences.contains("width_$id")) {
                widths[id] = preferences.getInt("width_$id", PenPreset.DEFAULT_WIDTH_LEVEL)
            }
        }
        return PenSettingsCodec.decode(
            activeTypeId = preferences.getString(KEY_ACTIVE_TYPE, null),
            colors = colors,
            widths = widths
        )
    }

    private fun loadSlot(slot: Int): PenSettings {
        val colors = mutableMapOf<String, Int>()
        val widths = mutableMapOf<String, Int>()
        for (type in com.betterhv.note.ink.PenType.entries) {
            val id = PenProfiles.stableId(type)
            val colorKey = slotKey(slot, "color_$id")
            val widthKey = slotKey(slot, "width_$id")
            if (preferences.contains(colorKey)) {
                colors[id] = preferences.getInt(colorKey, PenPreset.DEFAULT_COLOR_INDEX)
            }
            if (preferences.contains(widthKey)) {
                widths[id] = preferences.getInt(widthKey, PenPreset.DEFAULT_WIDTH_LEVEL)
            }
        }
        return PenSettingsCodec.decode(
            activeTypeId = preferences.getString(slotKey(slot, KEY_ACTIVE_TYPE), null),
            colors = colors,
            widths = widths
        )
    }

    fun save(settings: PenToolbarSettings) {
        preferences.edit().apply {
            putInt(KEY_ACTIVE_SLOT, settings.activeSlot)
            for (slot in 0 until PenToolbarSettings.SLOT_COUNT) {
                val encoded = PenSettingsCodec.encode(settings.settingsFor(slot))
                putString(slotKey(slot, KEY_ACTIVE_TYPE), encoded.activeTypeId)
                encoded.colors.forEach { (id, value) ->
                    putInt(slotKey(slot, "color_$id"), value)
                }
                encoded.widths.forEach { (id, value) ->
                    putInt(slotKey(slot, "width_$id"), value)
                }
            }
        }.apply()
    }

    private fun slotKey(slot: Int, key: String): String = "slot_${slot}_$key"

    private companion object {
        const val PREFERENCES_NAME = "pen_settings"
        const val KEY_ACTIVE_TYPE = "active_type"
        const val KEY_ACTIVE_SLOT = "active_slot"
    }
}

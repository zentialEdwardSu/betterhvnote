package com.betterhv.note

import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.PenType
import com.betterhv.note.ink.PressureCurve

/** One of the deliberately small, e-ink-friendly color choices in the pen panel. */
data class PenColorChoice(
    val id: String,
    val label: String,
    val argb: Int
)

object PenPalette {
    val colors: List<PenColorChoice> = listOf(
        PenColorChoice("black", "黑色", 0xFF000000.toInt()),
        PenColorChoice("dark_gray", "深灰", 0xFF737373.toInt()),
        PenColorChoice("light_gray", "浅灰", 0xFFCCCCCC.toInt()),
        PenColorChoice("white", "白色", 0xFFFFFFFF.toInt()),
        PenColorChoice("red", "红色", 0xFFFF0000.toInt()),
        PenColorChoice("blue", "蓝色", 0xFF005FFF.toInt()),
        PenColorChoice("green", "绿色", 0xFF008000.toInt()),
        PenColorChoice("yellow", "黄色", 0xFFEDED05.toInt())
    )
}

data class PenPreset(
    val colorIndex: Int = DEFAULT_COLOR_INDEX,
    val widthLevel: Int = DEFAULT_WIDTH_LEVEL
) {
    companion object {
        const val DEFAULT_COLOR_INDEX = 0
        const val DEFAULT_WIDTH_LEVEL = 1
    }
}

/** Current brush plus the independently remembered choices for all three brushes. */
data class PenSettings(
    val activeType: PenType = PenType.NormalPen,
    val presets: Map<PenType, PenPreset> = PenType.entries.associateWith { PenPreset() }
) {
    fun activePreset(): PenPreset = presetFor(activeType)

    fun presetFor(type: PenType): PenPreset = presets[type] ?: PenPreset()

    fun selectType(type: PenType): PenSettings = copy(activeType = type)

    fun updateActivePreset(transform: (PenPreset) -> PenPreset): PenSettings =
        copy(presets = presets + (activeType to transform(activePreset()).sanitized()))
}

/** Three toolbar pen shortcuts. Each slot owns a complete, independent brush configuration. */
data class PenToolbarSettings(
    val activeSlot: Int = 0,
    val slots: List<PenSettings> = List(SLOT_COUNT) { PenSettings() }
) {
    fun activeSettings(): PenSettings = settingsFor(activeSlot)

    fun settingsFor(slot: Int): PenSettings = slots.getOrNull(slot) ?: PenSettings()

    fun selectSlot(slot: Int): PenToolbarSettings =
        copy(activeSlot = slot.takeIf { it in 0 until SLOT_COUNT } ?: 0)

    fun updateSlot(slot: Int, settings: PenSettings): PenToolbarSettings {
        if (slot !in 0 until SLOT_COUNT) return this
        val normalized = MutableList(SLOT_COUNT) { settingsFor(it) }
        normalized[slot] = settings
        return copy(slots = normalized)
    }

    companion object {
        const val SLOT_COUNT = 3
    }
}

private fun PenPreset.sanitized(): PenPreset = PenPreset(
    colorIndex = colorIndex.takeIf { it in PenPalette.colors.indices }
        ?: PenPreset.DEFAULT_COLOR_INDEX,
    widthLevel = widthLevel.takeIf { it in PenProfiles.logicalWidths.indices }
        ?: PenPreset.DEFAULT_WIDTH_LEVEL
)

/**
 * Pure hvNote-compatible mappings. Keeping these independent of Android makes the ROM-facing
 * constants and model-specific width interpolation directly unit-testable.
 */
object PenProfiles {
    val logicalWidths: IntArray = intArrayOf(1, 5, 10, 15, 20)

    fun stableId(type: PenType): String = when (type) {
        PenType.NormalPen -> "normal"
        PenType.Pencil -> "pencil"
        PenType.Marker -> "marker"
    }

    fun typeFromStableId(id: String?): PenType? = when (id) {
        "normal" -> PenType.NormalPen
        "pencil" -> PenType.Pencil
        "marker" -> PenType.Marker
        else -> null
    }

    fun servicePen(type: PenType): Int = when (type) {
        PenType.NormalPen -> 6
        PenType.Pencil, PenType.Marker -> 15
    }

    /** hvNote's five logical settings linearly interpolated into device-specific actual pixels. */
    fun actualWidth(type: PenType, widthLevel: Int, model: String): Float {
        val safeLevel = widthLevel.takeIf { it in logicalWidths.indices }
            ?: PenPreset.DEFAULT_WIDTH_LEVEL
        val logical = logicalWidths[safeLevel]
        val (minimum, maximum) = widthRange(type, model)
        return minimum + (maximum - minimum) * ((logical - 1f) / 19f)
    }

    fun style(settings: PenSettings, model: String): PenStyle {
        val type = settings.activeType
        val preset = settings.activePreset().sanitized()
        val baseColor = PenPalette.colors[preset.colorIndex].argb
        return PenStyle(
            baseWidth = actualWidth(type, preset.widthLevel, model),
            color = if (type == PenType.Marker) markerColor(preset.colorIndex) else baseColor,
            pressureCurve = when (type) {
                PenType.NormalPen -> PressureCurve()
                PenType.Pencil -> PressureCurve(a = 0.20f, gamma = 1.0f)
                PenType.Marker -> PressureCurve(a = 1.0f, gamma = 1.0f)
            },
            penType = type
        )
    }

    /** Color argument accepted by HvPenDrawManager, matching hvNote's color/mono split. */
    fun serviceColor(style: PenStyle, colorHardware: Boolean): Int {
        if (colorHardware) return style.color

        val rgb = style.color and 0x00FFFFFF
        if (rgb == 0x00FFFFFF) return SERVICE_COLOR_WHITE
        if (style.penType == PenType.Marker) {
            return if (rgb == 0) SERVICE_COLOR_DARK_GRAY else SERVICE_COLOR_LIGHT_GRAY
        }
        return if (rgb == 0) SERVICE_COLOR_BLACK else SERVICE_COLOR_DARK_GRAY
    }

    fun markerColor(colorIndex: Int): Int = when (colorIndex) {
        0 -> 0x66000000
        1 -> 0x66737373
        2 -> 0x80CCCCCC.toInt()
        3 -> 0x66FFFFFF
        4 -> 0x4DFF0000
        5 -> 0x4D005FFF
        6 -> 0x4D008000
        7 -> 0x66EDED05
        else -> 0x66000000
    }

    private fun widthRange(type: PenType, model: String): Pair<Float, Float> {
        val family = when {
            model.startsWith("C10", ignoreCase = true) -> DeviceFamily.C10
            model.startsWith("N10Pro", ignoreCase = true) ||
                model.startsWith("M10", ignoreCase = true) -> DeviceFamily.N10_PRO_OR_M10
            else -> DeviceFamily.OTHER
        }
        return when (family) {
            DeviceFamily.C10 -> when (type) {
                PenType.Pencil -> 2f to 21f
                PenType.NormalPen -> 2f to 21f
                PenType.Marker -> 15f to 54f
            }
            DeviceFamily.N10_PRO_OR_M10 -> when (type) {
                PenType.Pencil -> 3f to 30f
                PenType.NormalPen -> 4f to 36f
                PenType.Marker -> 30f to 80f
            }
            DeviceFamily.OTHER -> when (type) {
                PenType.Pencil -> 2f to 22f
                PenType.NormalPen -> 3f to 26f
                PenType.Marker -> 21f to 60f
            }
        }
    }

    private enum class DeviceFamily { C10, N10_PRO_OR_M10, OTHER }

    const val SERVICE_COLOR_BLACK = 1
    const val SERVICE_COLOR_WHITE = 2
    const val SERVICE_COLOR_DARK_GRAY = 3
    const val SERVICE_COLOR_LIGHT_GRAY = 4
}

/** SharedPreferences uses this pure codec so invalid stored values have deterministic defaults. */
object PenSettingsCodec {
    data class Encoded(
        val activeTypeId: String,
        val colors: Map<String, Int>,
        val widths: Map<String, Int>
    )

    fun encode(settings: PenSettings): Encoded = Encoded(
        activeTypeId = PenProfiles.stableId(settings.activeType),
        colors = PenType.entries.associate { type ->
            PenProfiles.stableId(type) to settings.presetFor(type).sanitized().colorIndex
        },
        widths = PenType.entries.associate { type ->
            PenProfiles.stableId(type) to settings.presetFor(type).sanitized().widthLevel
        }
    )

    fun decode(
        activeTypeId: String?,
        colors: Map<String, Int>,
        widths: Map<String, Int>
    ): PenSettings {
        val presets = PenType.entries.associateWith { type ->
            val id = PenProfiles.stableId(type)
            PenPreset(
                colorIndex = colors[id] ?: PenPreset.DEFAULT_COLOR_INDEX,
                widthLevel = widths[id] ?: PenPreset.DEFAULT_WIDTH_LEVEL
            ).sanitized()
        }
        return PenSettings(
            activeType = PenProfiles.typeFromStableId(activeTypeId) ?: PenType.NormalPen,
            presets = presets
        )
    }
}

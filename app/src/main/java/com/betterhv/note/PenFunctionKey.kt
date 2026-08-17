package com.betterhv.note

enum class PenSideButton(val logName: String) {
    NONE("none"),
    SIDE_1("side1"),
    SIDE_2("side2"),
    SIDE_3("side3")
}

/** Exact port of hvNote's MemoView.checkPenbtnMode button-identification branch. */
object PenFunctionKey {
    const val HANVON_FUNCTION_TOOL_TYPE = 6
    private const val TOOL_TYPE_FINGER = 1
    private const val SOURCE_STYLUS = 0x00004002
    private const val SIDE_KEY_1_STATE = 0x20
    private const val SIDE_KEY_2_STATE = 0x40
    private const val SIDE_KEY_3_STATE = 0x80

    fun classify(toolType: Int, buttonState: Int, source: Int = 0): PenSideButton = when {
        // hvNote does not require TOOL_TYPE_STYLUS here. Some Hanvon ROMs encode
        // the primary side key solely as the vendor tool type 6.
        toolType == HANVON_FUNCTION_TOOL_TYPE || buttonState == SIDE_KEY_1_STATE ->
            PenSideButton.SIDE_1
        buttonState == SIDE_KEY_2_STATE -> PenSideButton.SIDE_2
        buttonState == SIDE_KEY_3_STATE -> PenSideButton.SIDE_3
        // Observed on the target Hanvon device: Side1 rewrites the tip contact
        // to toolType=FINGER without setting a button bit, while the event source
        // remains 0x5002 (TOUCHSCREEN | STYLUS). Requiring the stylus source keeps
        // ordinary finger input (normally source 0x1002) out of this fallback.
        toolType == TOOL_TYPE_FINGER && buttonState == 0 &&
            source and SOURCE_STYLUS == SOURCE_STYLUS -> PenSideButton.SIDE_1
        else -> PenSideButton.NONE
    }

    fun isPressed(toolType: Int, buttonState: Int, source: Int = 0): Boolean =
        classify(toolType, buttonState, source) == PenSideButton.SIDE_1

    /** UI click classification: explicit Side2/Side3 bits outrank the vendor Side1 tool fallback. */
    fun classifyClickModifier(toolType: Int, buttonState: Int, source: Int = 0): PenSideButton = when {
        buttonState and SIDE_KEY_3_STATE != 0 -> PenSideButton.SIDE_3
        buttonState and SIDE_KEY_2_STATE != 0 -> PenSideButton.SIDE_2
        buttonState and SIDE_KEY_1_STATE != 0 -> PenSideButton.SIDE_1
        else -> classify(toolType, buttonState, source)
    }
}

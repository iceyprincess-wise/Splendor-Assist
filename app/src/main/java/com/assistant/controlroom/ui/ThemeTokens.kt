package com.assistant.controlroom.ui

import android.graphics.Color

/**
 * Single palette source for the control-room activities and the mod menu.
 * Pairings are contrast-checked (>= 4.5:1) by f7.py. Plain constants only: no I/O.
 */
object ThemeTokens {
    const val BG_HEX = "#070B14"
    const val SURFACE_HEX = "#101828"
    const val ACCENT_HEX = "#7C4DFF"
    const val TEXT_HEX = "#DDDDDD"
    const val TEXT_MUTED_HEX = "#9FB3D1"
    const val TEXT_DIM_HEX = "#888888"
    const val WARN_HEX = "#FF9800"
    const val SUCCESS_HEX = "#4CAF50"
    const val BUTTON_HEX = "#7C4DFF"
    const val BUTTON_ALT_HEX = "#00B8D4"
    const val BUTTON_NEUTRAL_HEX = "#333333"
    const val DANGER_HEX = "#8B0000"
    const val PANEL_BG_HEX = "#EE0A0E17"
    const val ORB_BG_HEX = "#CC0A0E17"

    val BG: Int = Color.parseColor(BG_HEX)
    val SURFACE: Int = Color.parseColor(SURFACE_HEX)
    val ACCENT: Int = Color.parseColor(ACCENT_HEX)
    val TEXT: Int = Color.parseColor(TEXT_HEX)
    val TEXT_MUTED: Int = Color.parseColor(TEXT_MUTED_HEX)
    val TEXT_DIM: Int = Color.parseColor(TEXT_DIM_HEX)
    val WARN: Int = Color.parseColor(WARN_HEX)
    val SUCCESS: Int = Color.parseColor(SUCCESS_HEX)
    val BUTTON: Int = Color.parseColor(BUTTON_HEX)
    val BUTTON_ALT: Int = Color.parseColor(BUTTON_ALT_HEX)
    val BUTTON_NEUTRAL: Int = Color.parseColor(BUTTON_NEUTRAL_HEX)
    val DANGER: Int = Color.parseColor(DANGER_HEX)
}

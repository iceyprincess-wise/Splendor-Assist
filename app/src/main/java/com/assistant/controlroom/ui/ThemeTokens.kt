package com.assistant.controlroom.ui

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.graphics.toColorInt

/**
 * Single palette source for the control-room activities and the mod menu.
 * Plain constants only: no I/O.
 *
 * No66-D22: the old claim "contrast-checked (>= 4.5:1) by f7.py" was false —
 * no f7.py exists in the repo or workspace. Ratios below were computed
 * directly from the hex values (WCAG 2.x relative luminance) in round no66:
 * TEXT/BG 16.90, TEXT_MUTED/BG 9.22, TEXT_DIM/BG 5.55, WARN/BG 9.13,
 * SUCCESS/BG 11.79, white/BUTTON 4.81, white/DANGER 10.01,
 * white/BUTTON_NEUTRAL 12.63, dark-BG/BUTTON_ALT 12.79, ACCENT_TEXT/BG 6.29
 * — all >= 4.5:1. Pairings that FAIL and must not be used: white text on
 * BUTTON_ALT (was 2.38:1 on #00B8D4 — alt buttons now use dark BG text),
 * ACCENT as text on BG/SURFACE (4.09/3.69 — use ACCENT_TEXT instead).
 *
 * No66-D23/D24 (Q61-2 advance): values that duplicate a theme token now
 * EQUAL it — TEXT is theme colorOnSurface #E8EEF9, SUCCESS is theme
 * colorTertiary #00E676, BUTTON_ALT is brand cyan #00E5FF (theme
 * colorSecondary). ACCENT/SURFACE/BG/TEXT_MUTED already matched their
 * theme roles. TEXT_DIM stays #888888: the theme's dim token #5C7399
 * measures only 4.09:1 on BG and must not be unified (No66-D35).
 */
object ThemeTokens {
    const val BG_HEX = "#070B14"
    const val SURFACE_HEX = "#101828"
    const val ACCENT_HEX = "#7C4DFF"
    // No66-D25: lighter purple for TEXT use only. No single hex passes
    // both roles: ACCENT #7C4DFF as text is 4.09:1 on BG (fail) while as a
    // button background with white text it is 4.81:1 (pass).
    const val ACCENT_TEXT_HEX = "#9D7BFF"
    const val TEXT_HEX = "#E8EEF9"
    const val TEXT_MUTED_HEX = "#9FB3D1"
    const val TEXT_DIM_HEX = "#888888"
    const val WARN_HEX = "#FF9800"
    const val SUCCESS_HEX = "#00E676"
    const val BUTTON_HEX = "#7C4DFF"
    const val BUTTON_ALT_HEX = "#00E5FF"
    const val BUTTON_NEUTRAL_HEX = "#333333"
    const val DANGER_HEX = "#8B0000"
    const val PANEL_BG_HEX = "#EE0A0E17"
    const val ORB_BG_HEX = "#CC0A0E17"

    val BG: Int = BG_HEX.toColorInt()
    val SURFACE: Int = SURFACE_HEX.toColorInt()
    val ACCENT_TEXT: Int = ACCENT_TEXT_HEX.toColorInt()
    val TEXT: Int = TEXT_HEX.toColorInt()
    val TEXT_MUTED: Int = TEXT_MUTED_HEX.toColorInt()
    val TEXT_DIM: Int = TEXT_DIM_HEX.toColorInt()
    val WARN: Int = WARN_HEX.toColorInt()
    val SUCCESS: Int = SUCCESS_HEX.toColorInt()
    val BUTTON: Int = BUTTON_HEX.toColorInt()
    val BUTTON_ALT: Int = BUTTON_ALT_HEX.toColorInt()
    val BUTTON_NEUTRAL: Int = BUTTON_NEUTRAL_HEX.toColorInt()
    val DANGER: Int = DANGER_HEX.toColorInt()

    /**
     * Q61-2/Q66-B4 (batch 5b): ?attr/ resolution layer. The hex constants
     * above are FALLBACKS, not the source of truth — each themed role
     * resolves its theme attribute from the caller's theme at call time,
     * so a theme change re-skins the room without a code change. If the
     * theme does not define the attribute (or it does not resolve to a
     * color int), the hex fallback is used — today every resolved value
     * equals its hex fallback (No66 unified them), so adopting the
     * resolver changes no pixel, only the source of truth.
     *
     * Roles with NO theme attribute keep their hex values, with the
     * reason at the constant: ACCENT_TEXT (no attr, deliberately lighter
     * than ACCENT for text use), TEXT_DIM (theme dim #5C7399 fails
     * contrast, No66-D35), WARN, DANGER, BUTTON_NEUTRAL, PANEL_BG/ORB_BG.
     */
    fun resolve(context: Context, @AttrRes attr: Int, fallbackHex: String): Int {
        val out = TypedValue()
        return if (context.theme.resolveAttribute(attr, out, true) &&
            out.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            out.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            out.data
        } else {
            fallbackHex.toColorInt()
        }
    }

    fun bg(context: Context): Int =
        resolve(context, android.R.attr.colorBackground, BG_HEX)
    fun text(context: Context): Int =
        resolve(context, com.google.android.material.R.attr.colorOnSurface, TEXT_HEX)
    fun textMuted(context: Context): Int =
        resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant, TEXT_MUTED_HEX)
    fun success(context: Context): Int =
        resolve(context, com.google.android.material.R.attr.colorTertiary, SUCCESS_HEX)
    fun button(context: Context): Int =
        resolve(context, com.google.android.material.R.attr.colorPrimary, BUTTON_HEX)
    fun buttonAlt(context: Context): Int =
        resolve(context, com.google.android.material.R.attr.colorSecondary, BUTTON_ALT_HEX)
}

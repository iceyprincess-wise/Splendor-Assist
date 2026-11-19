package com.assistant.controlroom.ui


import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * PHASE4 UPGRADE: FutureRoomsActivity
 *
 * Room directory: real room cards with navigation to active subsystems.
 *
 * Active rooms:
 *   - AI Self-Heal Agent Monitor — navigates to AgentHubActivity, the
 *     single authoritative agent monitor (No66-D44: the inline agent
 *     screen that used to live here duplicated AgentHub, and its
 *     "Force Heal Cycle Now" button was a no-op whenever the daemon was
 *     already running — RuntimeSelfHealEngine.start() returns at once
 *     when running, so the button only showed a toast. AgentHub's
 *     button genuinely forces a cycle via InAppAgentCore.runNow()).
 *
 * Future stubs: 15 planned rooms listed below active rooms.
 *
 * Navigation: launched with intent.extra "room_label" (e.g. "Future
 * Rooms" from the home card). No deep-link screen is selected from it:
 * the old "agent" value had zero callers repo-wide (only "Future Rooms"
 * is ever passed, and the activity is exported=false, so no external
 * caller can pass it either) — the branch was dead and is removed.
 */
class FutureRoomsActivity : AppCompatActivity() {

    // Q61-2/Q66-B4 (batch 5b): the themed palette, resolved once from this
    // activity's theme — the theme is the source of truth, the ThemeTokens
    // hex values are fallbacks for any attribute the theme does not define.
    // Roles with no theme attribute (TEXT_DIM, WARN, BUTTON_NEUTRAL) keep
    // their hex constants. Other ThemeTokens users (Goalkeeper/Interception/
    // AgentHub/GameplayRoom activities, ModMenuController) stay on hex for
    // now — the resolver exists; they adopt one by one.
    private val paletteBg by lazy { ThemeTokens.bg(this) }
    private val paletteText by lazy { ThemeTokens.text(this) }
    private val paletteTextMuted by lazy { ThemeTokens.textMuted(this) }
    private val paletteSuccess by lazy { ThemeTokens.success(this) }
    private val paletteButton by lazy { ThemeTokens.button(this) }
    private val paletteButtonAlt by lazy { ThemeTokens.buttonAlt(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Shared UI helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(24), dp(16), dp(32))
        setBackgroundColor(paletteBg)
    }

    private fun mount(v: LinearLayout) {
        setContentView(ScrollView(this).apply {
            setBackgroundColor(paletteBg)
            addView(v, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
    }

    private fun text(list: LinearLayout, t: String, size: Float = 13f,
                     bold: Boolean = false, color: Int = paletteText): TextView {
        val tv = TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }
        list.addView(tv)
        return tv
    }

    private fun btn(list: LinearLayout, label: String,
                    bg: Int = paletteButton, onClick: () -> Unit) {
        list.addView(Button(this).apply {
            text = label; isAllCaps = false; setBackgroundColor(bg)
            // No66-D23: white text on BUTTON_ALT was 2.38:1; alt buttons
            // (now brand cyan #00E5FF) take dark text — 12.79:1.
            setTextColor(if (bg == paletteButtonAlt) paletteBg else Color.WHITE); textSize = 14f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(5), 0, dp(5))
            layoutParams = lp
            setOnClickListener { onClick() }
        })
    }

    private fun divider(list: LinearLayout) {
        list.addView(android.view.View(this).apply {
            setBackgroundColor(ThemeTokens.BUTTON_NEUTRAL)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.setMargins(0, dp(14), 0, dp(14))
            layoutParams = lp
        })
    }

    // ─────────────────────────────────────────────────────────────────────
    // HOME SCREEN — Room Cards
    // ─────────────────────────────────────────────────────────────────────
    private fun showHome() {
        val root = page()

        text(root, "SPLENDOR ROOMS", 22f, bold = true, color = Color.WHITE)
        text(root, "Control centers for every subsystem", 12f, color = ThemeTokens.TEXT_DIM)

        divider(root)

        text(root, "ACTIVE ROOMS", 13f, bold = true, color = paletteSuccess)

        btn(root, "🤖   AI Self-Heal Agent Monitor", paletteButtonAlt) {
            startActivity(Intent(this, AgentHubActivity::class.java))
        }
        text(root, "   Detects and fixes broken engine states while you play. Logs every fix to SplendorHealLog.txt", 11f, color = paletteTextMuted)

        text(root, "  ⚙️  Admin Settings — removed", 11f, color = ThemeTokens.TEXT_DIM)

        divider(root)

        text(root, "COMING SOON (not wired yet)", 13f, bold = true, color = ThemeTokens.WARN)

        val planned = listOf(
            "📊  Live Frame Monitor          — real-time VisionCore frame display",
            "🧠  Tactical Intelligence Room  — formation + behavior recognition",
            "🎯  Shot Accuracy Lab           — shooting lane analysis + keeper bias",
            "🛡️  Defensive Analysis Room     — intercept matrix + zone pressure",
            "📡  Network Quality Room        — packet loss + congestion sentinel",
            "🔥  Thermal Management Room     — device heat + engine duty cycle",
            "🏃  Player Tracking Room        — jersey segmentation + NMS tuning",
            "⚡  Input Response Room         — dispatch latency + touch IRQ stalls",
            "💾  Memory Optimization Room    — RAM tier + LMK lifecycle control",
            "🔄  Sync Monitor Room           — accessibility liveness + heal count",
            "📈  Match Analytics Room        — session stats + contribution rates",
            "🎮  Gameplay Replay Room        — DVR session review",
            "🩺  Health Diagnostics Room     — full engine audit on demand",
            "🌍  Formation Engine Room       — team shape + defensive line",
            "🏆  Keeper Command Room         — goalkeeper beast save configuration"
        )
        for (r in planned) {
            text(root, "  $r", 11f, color = ThemeTokens.TEXT_DIM)
        }

        divider(root)

        btn(root, "←  Back", ThemeTokens.BUTTON_NEUTRAL) { finish() }
        mount(root)
    }
}

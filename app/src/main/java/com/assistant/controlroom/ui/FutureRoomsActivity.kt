package com.assistant.controlroom.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.assistant.adapter.smartassist.RuntimeSelfHealEngine

/**
 * PHASE4 UPGRADE: FutureRoomsActivity
 *
 * Previous state: list of "Room XX • planned" placeholders.
 * Now: real room cards with navigation to active subsystems.
 *
 * Active rooms:
 *   - AI Self-Heal Agent Monitor (inline — shows heal events + live bus status)
 *   - Admin Settings (Engine Tuning)
 *
 * Future stubs: 15 planned rooms listed below active rooms.
 *
 * Navigation: can be launched with intent.extra "room_label" = "agent"
 * to open directly to the agent monitor screen.
 */
class FutureRoomsActivity : AppCompatActivity() {

    private enum class Screen { HOME, AGENT }
    private var screen = Screen.HOME

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var agentRefreshRunnable: Runnable? = null

    // Views held for live refresh in agent screen
    private var agentStatusView: TextView? = null
    private var agentEventsView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fromRoom = intent.getStringExtra("room_label")
        if (fromRoom == "agent") showAgent() else showHome()
    }

    override fun onDestroy() {
        agentRefreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        agentStatusView = null
        agentEventsView = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.AGENT -> showHome()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Shared UI helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(24), dp(16), dp(32))
        setBackgroundColor(Color.parseColor("#121212"))
    }

    private fun mount(v: LinearLayout) {
        agentRefreshRunnable?.let { refreshHandler.removeCallbacks(it)  }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            addView(v, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
    }

    private fun text(list: LinearLayout, t: String, size: Float = 13f,
                     bold: Boolean = false, color: Int = Color.parseColor("#DDDDDD")): TextView {
        val tv = TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }
        list.addView(tv)
        return tv
    }

    private fun btn(list: LinearLayout, label: String,
                    bg: Int = Color.parseColor("#1565C0"), onClick: () -> Unit) {
        list.addView(Button(this).apply {
            text = label; isAllCaps = false; setBackgroundColor(bg)
            setTextColor(Color.WHITE); textSize = 14f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(5), 0, dp(5))
            layoutParams = lp
            setOnClickListener { onClick() }
        })
    }

    private fun divider(list: LinearLayout) {
        list.addView(android.view.View(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
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
        screen = Screen.HOME
        val root = page()

        text(root, "SPLENDOR ROOMS", 22f, bold = true, color = Color.WHITE)
        text(root, "Control centers for every subsystem", 12f, color = Color.parseColor("#888888"))

        divider(root)

        text(root, "ACTIVE ROOMS", 13f, bold = true, color = Color.parseColor("#4CAF50"))

        btn(root, "🤖   AI Self-Heal Agent Monitor", Color.parseColor("#6A1B9A")) {
            showAgent()
        }
        text(root, "   Detects and fixes broken engine states while you play. Logs every fix to Splendor_HealLog.txt", 11f, color = Color.parseColor("#AAAAAA"))

        text(root, "  ⚙️  Admin Settings — removed", 11f, color = Color.parseColor("#444444"))

        divider(root)

        text(root, "PLANNED ROOMS", 13f, bold = true, color = Color.parseColor("#FF9800"))

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
            text(root, "  $r", 11f, color = Color.parseColor("#666666"))
        }

        divider(root)

        btn(root, "←  Back", Color.parseColor("#333333")) { finish() }
        mount(root)
    }

    // ─────────────────────────────────────────────────────────────────────
    // AGENT SCREEN — AI Self-Heal Monitor (inline, no separate Activity)
    // ─────────────────────────────────────────────────────────────────────
    private fun showAgent() {
        screen = Screen.AGENT
        val root = page()

        text(root, "🤖  AI SELF-HEAL AGENT", 20f, bold = true, color = Color.WHITE)
        text(root, "Runtime monitor: detects broken engine states and fixes them in-memory while you play",
            11f, color = Color.parseColor("#AAAAAA"))

        divider(root)

        text(root, "LIVE STATUS", 13f, bold = true, color = Color.parseColor("#4CAF50"))

        val statusTv = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#80FF80"))
            setPadding(0, dp(4), 0, dp(8))
            typeface = Typeface.MONOSPACE
            text = "Loading agent status..."
        }
        root.addView(statusTv)
        agentStatusView = statusTv

        divider(root)

        text(root, "HEAL EVENT LOG  (latest 20)", 13f, bold = true, color = Color.parseColor("#FF9800"))

        val eventsTv = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, dp(4), 0, dp(8))
            typeface = Typeface.MONOSPACE
            text = "Waiting for heal events..."
        }
        root.addView(eventsTv)
        agentEventsView = eventsTv

        divider(root)

        btn(root, "↺  Force Heal Cycle Now", Color.parseColor("#6A1B9A")) {
            try {
                RuntimeSelfHealEngine.start()
                android.widget.Toast.makeText(this, "Heal cycle triggered", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        btn(root, "←  Back to Rooms", Color.parseColor("#333333")) { showHome() }

        mount(root)

        // Start live refresh (3s interval)
        val refreshRunnable = object : Runnable {
            override fun run() {
                if (screen != Screen.AGENT) return
                try {
                    agentStatusView?.text = RuntimeSelfHealEngine.getStatusSummary()

                    val events = RuntimeSelfHealEngine.healEvents
                    agentEventsView?.text = if (events.isEmpty()) {
                        "No heal events yet.\nAgent is monitoring...\n\nIf game is running and no events appear\nafter 30s, the agent detected no problems."
                    } else {
                        events.reversed().take(20).joinToString("\n\n") { ev ->
                            val icon = when (ev.severity) {
                                "FIXED"    -> "✅ FIXED"
                                "CRITICAL" -> "🔴 CRITICAL"
                                "WARNING"  -> "🟡 WARNING"
                                else       -> "ℹ️ INFO"
                            }
                            "$icon  [${ev.timestamp}]  ${ev.category}\n" +
                            "DETECTED: ${ev.detected.take(160)}\n" +
                            "FIX: ${ev.fix.take(160)}"
                        }
                    }
                } catch (_: Throwable) {}
                refreshHandler.postDelayed(this, 3000L)
            }
        }
        agentRefreshRunnable = refreshRunnable
        refreshHandler.post(refreshRunnable)
    }
}

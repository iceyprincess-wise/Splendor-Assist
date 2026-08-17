package com.assistant.controlroom.ui

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
import com.assistant.adapter.smartassist.InAppAgentCore

/**
 * Agent Hub Room (Item 4/6)
 *
 * The single authoritative Activity for the AI Self-Heal Agent monitor.
 * Opened by cardAccessibility (renamed to AGENT HUB ROOM on the home screen).
 *
 * Shows:
 *   - Live agent status (3s refresh)
 *   - Last 20 heal events with severity icons
 *   - Force Heal Cycle button
 *
 * This is the only home card approved for agent content.
 */
class AgentHubActivity : AppCompatActivity() {

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var agentStatusView: TextView? = null
    private var agentCoreView: TextView? = null
    private var agentEventsView: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(24), dp(16), dp(32))
        setBackgroundColor(Color.parseColor("#0A0A1A"))
    }

    private fun text(
        list: LinearLayout, t: String, size: Float = 13f,
        bold: Boolean = false, color: Int = Color.parseColor("#DDDDDD")
    ): TextView {
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
            lp.setMargins(0, dp(6), 0, dp(6)); layoutParams = lp
            setOnClickListener { onClick() }
        })
    }

    private fun divider(list: LinearLayout) {
        list.addView(android.view.View(this).apply {
            setBackgroundColor(Color.parseColor("#2A2A3A"))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.setMargins(0, dp(14), 0, dp(14)); layoutParams = lp
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onDestroy() {
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        agentStatusView = null
        agentCoreView = null
        agentEventsView = null
        super.onDestroy()
    }

    private fun buildUi() {
        val root = page()

        text(root, "🤖  AGENT HUB ROOM", 22f, bold = true, color = Color.WHITE)
        text(root, "AI Self-Heal Agent — runtime monitor for broken engine states",
            11f, color = Color.parseColor("#8888AA"))

        divider(root)

        text(root, "LIVE STATUS", 13f, bold = true, color = Color.parseColor("#4CAF50"))

        val statusTv = TextView(this).apply {
            textSize = 11f; setTextColor(Color.parseColor("#80FF80"))
            setPadding(0, dp(4), 0, dp(8)); typeface = Typeface.MONOSPACE
            text = "Loading agent status..."
        }
        root.addView(statusTv)
        agentStatusView = statusTv

        val coreTv = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#9FE7FF"))
            setPadding(0, dp(4), 0, dp(8))
            typeface = Typeface.MONOSPACE
            text = "InAppAgentCore: starting..."
        }
        root.addView(coreTv)
        agentCoreView = coreTv

        divider(root)

        text(root, "HEAL EVENT LOG  (latest 20)", 13f, bold = true,
            color = Color.parseColor("#FF9800"))

        val eventsTv = TextView(this).apply {
            textSize = 10f; setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, dp(4), 0, dp(8)); typeface = Typeface.MONOSPACE
            text = "Waiting for heal events..."
        }
        root.addView(eventsTv)
        agentEventsView = eventsTv

        divider(root)

        btn(root, "↺  Force Heal Cycle Now", Color.parseColor("#6A1B9A")) {
            try {
                InAppAgentCore.runNow()
                android.widget.Toast.makeText(this, "Agent cycle triggered",
                    android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Error: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        btn(root, "←  Back", Color.parseColor("#333333")) { finish() }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A1A"))
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        startRefresh()
    }

    private fun startRefresh() {
        val r = object : Runnable {
            override fun run() {
                try {
                    agentStatusView?.text = RuntimeSelfHealEngine.getStatusSummary()

                    val core = InAppAgentCore.snapshot()
                    agentCoreView?.text =
                        "IN-APP AGENT CORE\n" +
                        "Running    : ${core.running}\n" +
                        "Cycles     : ${core.cycles}\n" +
                        "Last action: ${core.lastAction}\n" +
                        "Reason     : ${core.lastReason}\n" +
                        "Verified   : ${core.lastVerified}\n" +
                        "Verifier   : ${core.lastVerification}"

                    val events = RuntimeSelfHealEngine.healEvents
                    agentEventsView?.text = if (events.isEmpty()) {
                        "No heal events yet.\nAgent is monitoring...\n\nIf the game is running and no events\nappear after 30s, the agent found no problems."
                    } else {
                        events.reversed().take(20).joinToString("\n\n") { ev ->
                            val icon = when (ev.severity) {
                                "FIXED"    -> "✅ FIXED"
                                "CRITICAL" -> "🔴 CRITICAL"
                                "WARNING"  -> "🟡 WARNING"
                                else       -> "ℹ️ INFO"
                            }
                            "$icon  [${ev.timestamp}]  ${ev.category}\n" +
                            "DETECTED: ${ev.detected}\n" +
                            "FIX: ${ev.fix}"
                        }
                    }
                } catch (_: Throwable) {}
                refreshHandler.postDelayed(this, 3_000L)
            }
        }
        refreshRunnable = r
        refreshHandler.post(r)
    }
}

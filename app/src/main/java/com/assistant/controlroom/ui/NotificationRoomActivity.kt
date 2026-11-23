package com.assistant.controlroom.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.assistant.RuntimeHealthMonitor

/**
 * Notification Room (UI-2) — where the app drops a notification when it
 * has an issue. The list is built ONLY from the live health monitor's
 * degraded reasons (RuntimeHealthMonitor.snapshot().degradedReasons) —
 * no fake notifications, nothing invented. The dashboard Notifications
 * card badge shows activeIssues().size, so the badge and this room
 * always agree (same source).
 *
 * Tapping an issue opens the Diagnosis Room, so Ed can see what is
 * wrong and then go to the diagnosis. When there are no issues the
 * room says so plainly instead of showing an empty list.
 *
 * Refresh: once on open, then only on the manual "Refresh Now" button
 * (same InAppLiveDisplay pattern as GameplayRoomActivity — no live
 * refresh loop, so this room can never become a bottleneck).
 */
class NotificationRoomActivity : AppCompatActivity() {

    data class Issue(val code: String, val title: String, val detail: String)

    companion object {
        /**
         * The single source of truth for active issues, for this room
         * and for the dashboard badge. One issue per degraded reason
         * the health monitor is reporting right now.
         */
        fun activeIssues(): List<Issue> {
            val reasons = try {
                RuntimeHealthMonitor.snapshot().degradedReasons
            } catch (_: Throwable) {
                return emptyList()
            }
            return reasons.map { code -> Issue(code, titleFor(code), detailFor(code)) }
        }

        private fun titleFor(code: String): String = when (code) {
            "accessibility-not-ready" -> "Accessibility not ready"
            "capture-not-ready" -> "Capture not ready"
            "frame-stale" -> "Frame stale"
            "decision-stale" -> "Decision stale"
            "bus-idle" -> "Bus idle"
            "dispatch-stale" -> "Dispatch stale"
            "gameplay-idle" -> "Gameplay idle"
            "booster-not-ready" -> "Booster not ready"
            "emergency-rejected" -> "Emergency save rejected"
            else -> code
        }

        private fun detailFor(code: String): String = when (code) {
            "accessibility-not-ready" -> "The accessibility service is not enabled."
            "capture-not-ready" -> "Screen capture is not ready."
            "frame-stale" -> "No new game frames in the last 3 seconds."
            "decision-stale" -> "The decision loop has not produced a fresh decision."
            "bus-idle" -> "No offers on the adapter bus."
            "dispatch-stale" -> "A requested gesture was not dispatched."
            "gameplay-idle" -> "No gameplay engine is producing."
            "booster-not-ready" -> "The booster fleet is not ready."
            "emergency-rejected" -> "A save was rejected and never reached the bus."
            else -> "An issue was reported by the health monitor."
        }
    }

    private var listContainer: LinearLayout? = null
    private var summaryView: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI-2: edge-to-edge (targetSdk 36 enforces it) — draw behind
        // the system bars; the insets are applied to the root ScrollView
        // below so no notification sits under the status / navigation bars.
        enableEdgeToEdge()
        buildUi()
        refreshNow()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(20), dp(14), dp(40))
            setBackgroundColor(ThemeTokens.BG)
        }

        val titleTv = TextView(this).apply {
            text = "🔔  NOTIFICATIONS"; textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(titleTv)

        val subTv = TextView(this).apply {
            text = "Issues the app found · tap one to see the diagnosis"
            textSize = 10f; setTextColor(ThemeTokens.TEXT_MUTED)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(subTv)

        summaryView = TextView(this).apply {
            text = "Loading…"; textSize = 12f
            setTextColor(ThemeTokens.TEXT_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(summaryView)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listContainer)

        val refreshBtn = Button(this).apply {
            text = "↺  Refresh Now"; isAllCaps = false
            setBackgroundColor(ThemeTokens.BUTTON)
            setTextColor(Color.WHITE); textSize = 13f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(12), 0, dp(6)); layoutParams = lp
            setOnClickListener { refreshNow() }
        }
        root.addView(refreshBtn)

        val backBtn = Button(this).apply {
            text = "←  Back"; isAllCaps = false
            setBackgroundColor(ThemeTokens.SURFACE)
            setTextColor(Color.WHITE); textSize = 13f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(6)); layoutParams = lp
            setOnClickListener { finish() }
        }
        root.addView(backBtn)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(ThemeTokens.BG)
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // UI-2: edge-to-edge insets on the root ScrollView.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    private fun issueBox(issue: Issue) {
        val container = listContainer ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.SURFACE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(6)); layoutParams = lp
            isClickable = true
            isFocusable = true
            // Tap an issue to see what is wrong, then go to the diagnosis.
            setOnClickListener {
                startActivity(Intent(this@NotificationRoomActivity, DiagnosisRoomActivity::class.java))
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        top.addView(TextView(this).apply {
            text = "●"; textSize = 14f
            setTextColor("#FF5252".toColorInt())
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8); layoutParams = lp
        })
        top.addView(TextView(this).apply {
            text = issue.title; textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        })
        row.addView(top)
        row.addView(TextView(this).apply {
            text = issue.detail; textSize = 11f
            setTextColor(ThemeTokens.TEXT_MUTED)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4); layoutParams = lp
        })
        row.addView(TextView(this).apply {
            text = "Tap to open Diagnosis →"; textSize = 11f
            setTextColor(ThemeTokens.ACCENT_TEXT)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6); layoutParams = lp
        })
        container.addView(row)
    }

    private fun refreshNow() {
        val container = listContainer ?: return
        container.removeAllViews()
        try {
            val issues = activeIssues()
            if (issues.isEmpty()) {
                summaryView?.text = "No issues — everything is live."
                summaryView?.setTextColor(ThemeTokens.SUCCESS)
                container.addView(TextView(this).apply {
                    text = "Nothing is wrong right now. When the app finds an issue it appears here."
                    textSize = 12f
                    setTextColor(ThemeTokens.TEXT_MUTED)
                    setPadding(0, dp(8), 0, dp(8))
                })
            } else {
                summaryView?.text = "${issues.size} issue${if (issues.size == 1) "" else "s"}"
                summaryView?.setTextColor("#FF5252".toColorInt())
                issues.forEach { issueBox(it) }
            }
        } catch (t: Throwable) {
            try { android.util.Log.w("NotificationRoom", "refreshNow failed", t) } catch (_: Throwable) {}
            try { summaryView?.text = "Refresh failed: ${t.message ?: t.javaClass.simpleName}" } catch (_: Throwable) {}
        }
    }
}

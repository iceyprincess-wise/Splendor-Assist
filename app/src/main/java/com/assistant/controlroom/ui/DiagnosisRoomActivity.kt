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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.assistant.RuntimeCoordinator
import com.assistant.RuntimeHealthMonitor
import com.assistant.controlroom.AdapterControlRoomSync
import com.assistant.runtime.GameplayEngineRegistry

/**
 * Diagnosis Room (UI-2) — the page of all the core engines and files,
 * each a tappable box showing live / dead / wired / off status.
 *
 * It is NOT a log viewer: there is deliberately NO logging in this file
 * (no logLine, no file writes, no log viewer). The boxes read the SAME
 * live snapshots the dashboard bulbs read (RuntimeCoordinator.
 * runtimeState, RuntimeHealthMonitor.runtimeHealthSnapshot,
 * GameplayEngineRegistry.registryRuntimeSnapshot,
 * AdapterControlRoomSync.snapshot), so every status is truth, never a
 * hardcoded value.
 *
 * Status vocabulary (all derived from the live value):
 *   LIVE  — the value is true, a counter is > 0, or a room is enabled.
 *   DEAD  — the value is false AND a degraded reason names it (a real
 *           issue, same reason codes RuntimeHealthMonitor emits).
 *   WIRED — the entry is registered (present in the snapshot) but
 *           currently false / 0 with no degraded reason (wired in,
 *           just not active).
 *   OFF   — explicitly disabled (a control room that is off).
 *
 * Refresh: once on open, then only on the manual "Refresh Now" button
 * (same InAppLiveDisplay pattern as GameplayRoomActivity — no live
 * refresh loop, so this room can never become a bottleneck).
 * Tapping a box shows that entry's detail (name, status, value) as a
 * toast; there is no separate detail page in this piece.
 */
class DiagnosisRoomActivity : AppCompatActivity() {

    private enum class DiagStatus { LIVE, DEAD, WIRED, OFF }

    private data class DiagEntry(
        val name: String,
        val status: DiagStatus,
        val value: String,
        val detail: String
    )

    private var listContainer: LinearLayout? = null
    private var summaryView: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun statusColor(status: DiagStatus): Int = when (status) {
        DiagStatus.LIVE -> "#00E676".toColorInt()
        DiagStatus.DEAD -> "#FF5252".toColorInt()
        DiagStatus.WIRED -> "#FFC107".toColorInt()
        DiagStatus.OFF -> "#9FB3D1".toColorInt()
    }

    private fun statusLabel(status: DiagStatus): String = when (status) {
        DiagStatus.LIVE -> "LIVE"
        DiagStatus.DEAD -> "DEAD"
        DiagStatus.WIRED -> "WIRED"
        DiagStatus.OFF -> "OFF"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI-2: edge-to-edge (targetSdk 36 enforces it) — draw behind
        // the system bars; the insets are applied to the root ScrollView
        // below so no box sits under the status / navigation bars.
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
            text = "📊  DIAGNOSIS ROOM"; textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(titleTv)

        val subTv = TextView(this).apply {
            text = "Live engine + file status · tap a box for its detail · no logs here"
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

    private fun section(title: String) {
        val container = listContainer ?: return
        val tv = TextView(this).apply {
            text = title; textSize = 13f
            setTextColor(ThemeTokens.ACCENT_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        container.addView(tv)
        container.addView(android.view.View(this).apply {
            setBackgroundColor(ThemeTokens.SURFACE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.setMargins(0, 0, 0, dp(6)); layoutParams = lp
        })
    }

    private fun box(entry: DiagEntry) {
        val container = listContainer ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ThemeTokens.SURFACE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(6)); layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Toast.makeText(
                    this@DiagnosisRoomActivity,
                    "${entry.name}: ${statusLabel(entry.status)} — ${entry.detail}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        val dot = TextView(this).apply {
            text = "●"; textSize = 14f
            setTextColor(statusColor(entry.status))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8); layoutParams = lp
        }
        row.addView(dot)
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        mid.addView(TextView(this).apply {
            text = entry.name; textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT)
        })
        mid.addView(TextView(this).apply {
            text = entry.value; textSize = 11f
            setTextColor(ThemeTokens.TEXT_MUTED)
        })
        row.addView(mid)
        row.addView(TextView(this).apply {
            text = statusLabel(entry.status); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(statusColor(entry.status))
        })
        container.addView(row)
    }

    private fun refreshNow() {
        val container = listContainer ?: return
        container.removeAllViews()
        try {
            val runtime = try { RuntimeCoordinator.runtimeState() } catch (_: Throwable) { emptyMap<String, Any>() }
            val health = try { RuntimeHealthMonitor.runtimeHealthSnapshot() } catch (_: Throwable) { emptyMap<String, Any>() }
            val registry = try { GameplayEngineRegistry.registryRuntimeSnapshot() } catch (_: Throwable) { emptyMap<String, Any>() }
            val rooms = try { AdapterControlRoomSync.snapshot() } catch (_: Throwable) { emptyList() }
            val reasons = (health["degradedReasons"] as? String ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

            var live = 0
            var dead = 0
            var wired = 0
            var off = 0
            fun tally(s: DiagStatus) = when (s) {
                DiagStatus.LIVE -> live++
                DiagStatus.DEAD -> dead++
                DiagStatus.WIRED -> wired++
                DiagStatus.OFF -> off++
            }

            // RUNTIME — same live snapshot the dashboard RUNTIME bulbs read.
            section("RUNTIME")
            fun runtimeBool(key: String, label: String, reason: String?): DiagEntry {
                val v = runtime[key] as? Boolean ?: false
                val status = when {
                    v -> DiagStatus.LIVE
                    reason != null && reasons.contains(reason) -> DiagStatus.DEAD
                    else -> DiagStatus.WIRED
                }
                tally(status)
                return DiagEntry(label, status, if (v) "Ready" else "Not ready", "$label is ${if (v) "ready" else "not ready"}")
            }
            listOf(
                runtimeBool("permissionsVerified", "Permissions", null),
                runtimeBool("accessibilityReady", "Accessibility", "accessibility-not-ready"),
                runtimeBool("captureReady", "Capture", "capture-not-ready"),
                runtimeBool("boosterReady", "Booster", "booster-not-ready"),
                runtimeBool("enginesWarm", "Engines warm", null),
                runtimeBool("busEnabled", "Bus", "bus-idle"),
                runtimeBool("runtimeReady", "Runtime ready", null)
            ).forEach { box(it) }
            val pending = (runtime["busPending"] as? Number)?.toLong() ?: 0L
            val pendingStatus = if (pending == 0L) DiagStatus.LIVE else DiagStatus.WIRED
            tally(pendingStatus)
            box(DiagEntry("Bus pending", pendingStatus, "$pending pending", "Bus pending count is $pending"))

            // HEALTH — same live snapshot the dashboard HEALTH bulbs read.
            section("HEALTH")
            fun aliveBool(key: String, label: String, reason: String): DiagEntry {
                val v = health[key] as? Boolean ?: false
                val status = when {
                    v -> DiagStatus.LIVE
                    reasons.contains(reason) -> DiagStatus.DEAD
                    else -> DiagStatus.WIRED
                }
                tally(status)
                return DiagEntry(label, status, if (v) "Alive" else "Not alive", "$label is ${if (v) "alive" else "not alive"}")
            }
            listOf(
                aliveBool("accessibilityAlive", "Accessibility", "accessibility-not-ready"),
                aliveBool("overlayAlive", "Overlay", "capture-not-ready"),
                aliveBool("frameAlive", "Frame", "frame-stale"),
                aliveBool("decisionAlive", "Decision", "decision-stale"),
                aliveBool("busAlive", "Bus", "bus-idle"),
                aliveBool("dispatchAlive", "Dispatch", "dispatch-stale"),
                aliveBool("gameplayAlive", "Gameplay", "gameplay-idle"),
                aliveBool("boosterAlive", "Booster", "booster-not-ready")
            ).forEach { box(it) }
            val evaluations = (health["evaluations"] as? Number)?.toLong() ?: 0L
            val evalStatus = if (evaluations > 0) DiagStatus.LIVE else DiagStatus.WIRED
            tally(evalStatus)
            box(DiagEntry("Health checks", evalStatus, "$evaluations checks", "Health evaluations run: $evaluations"))

            // REGISTRY — same live snapshot the dashboard REGISTRY bulbs read.
            section("REGISTRY")
            fun countEntry(key: String, label: String): DiagEntry {
                val v = (registry[key] as? Number)?.toLong() ?: 0L
                val status = if (v > 0) DiagStatus.LIVE else DiagStatus.WIRED
                tally(status)
                return DiagEntry(label, status, "$v", "$label count is $v")
            }
            listOf(
                countEntry("engines", "Engines"),
                countEntry("collectCycles", "Collect cycles"),
                countEntry("contributed", "Contributed"),
                countEntry("generation", "Generation"),
                countEntry("collisions", "Collisions")
            ).forEach { box(it) }

            // CONTROL ROOMS — the three tuners, from the same snapshot
            // the dashboard room bulbs read.
            section("CONTROL ROOMS")
            rooms.forEach { room ->
                val enabled = try { room.enabled } catch (_: Throwable) { false }
                val intensity = try { room.intensity } catch (_: Throwable) { 0 }
                val label = when (room.adapterId) {
                    "smart_assist" -> "Smart Assist"
                    "goalkeeper" -> "Goalkeeper"
                    "interception" -> "Interception"
                    else -> room.adapterId
                }
                val status = if (enabled) DiagStatus.LIVE else DiagStatus.OFF
                tally(status)
                box(DiagEntry(label, status, if (enabled) "ACTIVE $intensity%" else "OFF", "$label is ${if (enabled) "active at $intensity%" else "off"}"))
            }

            summaryView?.text = "Live $live · Dead $dead · Wired $wired · Off $off"
            summaryView?.setTextColor(if (dead > 0) "#FF5252".toColorInt() else ThemeTokens.TEXT_MUTED)
        } catch (t: Throwable) {
            try { android.util.Log.w("DiagnosisRoom", "refreshNow failed", t) } catch (_: Throwable) {}
            try { summaryView?.text = "Refresh failed: ${t.message ?: t.javaClass.simpleName}" } catch (_: Throwable) {}
        }
    }
}

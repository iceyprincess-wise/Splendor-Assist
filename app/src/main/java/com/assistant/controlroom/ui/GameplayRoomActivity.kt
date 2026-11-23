package com.assistant.controlroom.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.assistant.CaptaincySkillEngine
import com.assistant.ConAIEngine
import com.assistant.CongestionSentinelEngine
import com.assistant.ConnectionHealEngine
import com.assistant.CrowdingZoneDetector
import com.assistant.DashboardInjector
import com.assistant.RuntimeDecisionLoop
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.AnalyticsTheaterActivity
import com.assistant.diagnostic.CrashInspectorActivity

import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gameplay Room (Items 4 + 6) — NEW UI (dashboard setup).
 *
 * The raw truth room for every gameplay engine and contributor in
 * adapter_smartassist. Shows live state, request queue, expiry,
 * effectiveness, and whether each engine/amplifier is working or dead.
 *
 * Also exposes the Captaincy designation toggle (Item 6 integration spec):
 *   CaptaincySkillEngine.setCaptainDesignated(true/false)
 *
 * Layout follows the dashboard: centred header, a truth summary, then
 * one bulb section per live source (DECISION LOOP, AMPLIFIERS, CROWDING,
 * SIGNAL BUS) with the counters in the middle, parameters/toggles inside
 * their sections, and the diagnostic-room + control buttons at the foot.
 * Every value comes from the same live snapshots the old room read —
 * nothing is hardcoded. Bulb colour is derived from the live value
 * (GREEN = live/good, YELLOW = waiting/not yet, RED = an issue).
 *
 * Logs to /sdcard/Splendor-Assist/gameplaylogs.txt on every refresh.
 * no59 ITEM 4: the 2s periodic refresh is STOPPED (InAppLiveDisplay,
 * default OFF), so the log line is now written once per manual
 * "Refresh Now" tap instead of every 2 seconds while the room is open.
 */
class GameplayRoomActivity : AppCompatActivity() {

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    // Dynamic containers — rebuilt on every refresh (same pattern as
    // the dashboard's renderBulbSection). Toggle rows are built once
    // in buildUi and are never rebuilt, so Switch state is preserved.
    private var summaryView: TextView? = null
    private var decisionGrid: LinearLayout? = null
    private var decisionDetailView: TextView? = null
    private var amplifierVerdictContainer: LinearLayout? = null
    private var amplifierGrid: LinearLayout? = null
    private var crowdVerdictContainer: LinearLayout? = null
    private var crowdGrid: LinearLayout? = null
    private var busGrid: LinearLayout? = null

    private val logFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val LOG_FILE: File
        get() = SplendorStorageRoot.file("gameplaylogs.txt")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Bulb model — same as the dashboard (MainActivity): GREEN = live /
    // good (or a live counter > 0), YELLOW = waiting / not yet, RED = an
    // issue. Nothing here is hardcoded: every value comes from the live
    // snapshot maps passed in.
    private enum class BulbState { GREEN, YELLOW, RED }

    private data class BulbEntry(val label: String, val state: BulbState, val value: String)

    private fun bulbColor(state: BulbState): Int = when (state) {
        BulbState.GREEN -> "#00E676".toColorInt()
        BulbState.YELLOW -> "#FFC107".toColorInt()
        BulbState.RED -> "#FF5252".toColorInt()
    }

    // UI-BULBS: framework text colours (textColorPrimary/Secondary)
    // resolve to a ColorStateList, not a plain colour — TypedValue.data
    // is 0 for the CSL reference, and setTextColor(0) is fully
    // transparent. Resolve the CSL and take its default colour; fall
    // back to the theme's on-surface colour if the attribute does not
    // resolve at all. (Same fix as MainActivity.themeColor.)
    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, tv, true)) return "#E8EEF9".toColorInt()
        if (tv.resourceId != 0) {
            return try {
                resources.getColorStateList(tv.resourceId, theme)?.defaultColor ?: tv.data
            } catch (_: Exception) {
                tv.data
            }
        }
        return tv.data
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(20), dp(14), dp(40))
        setBackgroundColor(ThemeTokens.BG)
    }

    // Centred section banner (dashboard style): centred title + divider.
    private fun section(list: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title; textSize = 13f
            // No66-D25: ACCENT as text was 4.09:1 on BG (fail); ACCENT_TEXT
            // is 6.29:1. ACCENT itself stays for button backgrounds.
            setTextColor(ThemeTokens.ACCENT_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        list.addView(tv)
        list.addView(android.view.View(this).apply {
            setBackgroundColor(ThemeTokens.SURFACE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.setMargins(0, 0, 0, dp(6)); layoutParams = lp
        })
    }

    // Two-column centred bulb grid. Rebuilt on every refresh.
    private fun renderBulbGrid(container: LinearLayout, entries: List<BulbEntry>) {
        container.removeAllViews()
        entries.chunked(2).forEach { row ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            }
            row.forEach { entry -> rowLayout.addView(buildBulbCell(entry)) }
            if (row.size == 1) {
                rowLayout.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            container.addView(rowLayout)
        }
    }

    // Centred bulb cell (dashboard buildBulbCell + UI-3 centring): the
    // dot+label row is WRAP_CONTENT and centred, the value is centred.
    private fun buildBulbCell(entry: BulbEntry): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(4); marginEnd = dp(4) }
            setBackgroundResource(com.assistant.overlay.R.drawable.panel_surface)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val dot = TextView(this).apply {
            text = "●"; textSize = 14f
            setTextColor(bulbColor(entry.state))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
        val label = TextView(this).apply {
            text = entry.label; textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        top.addView(dot)
        top.addView(label)
        val value = TextView(this).apply {
            text = entry.value; textSize = 11f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        cell.addView(top)
        cell.addView(value)
        return cell
    }

    // Status box (Diagnosis Room pattern): SURFACE row with a coloured
    // dot, a bold name, the live verdict as its value, and a short
    // status word on the right. Tap shows the detail as a toast.
    private fun statusBox(label: String, verdict: String, state: BulbState,
                          shortLabel: String, detail: String): LinearLayout {
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
                Toast.makeText(this@GameplayRoomActivity,
                    "$label: $shortLabel — $detail", Toast.LENGTH_LONG).show()
            }
        }
        val dot = TextView(this).apply {
            text = "●"; textSize = 14f
            setTextColor(bulbColor(state))
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
            text = label; textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT)
        })
        mid.addView(TextView(this).apply {
            text = verdict; textSize = 11f
            setTextColor(ThemeTokens.TEXT_MUTED)
        })
        row.addView(mid)
        row.addView(TextView(this).apply {
            text = shortLabel; textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(bulbColor(state))
        })
        return row
    }

    // Mono detail card inside a SURFACE box (for the fields that do not
    // fit a bulb: last action, last weight, freshness, ConAI, mirror).
    private fun detailCard(list: LinearLayout, initText: String = ""): TextView {
        val tv = TextView(this).apply {
            text = initText; textSize = 10f
            setTextColor(ThemeTokens.TEXT)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(ThemeTokens.SURFACE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(2), 0, dp(6)); layoutParams = lp
        }
        list.addView(tv)
        return tv
    }

    // Toggle row inside a SURFACE card. Built once; never rebuilt on
    // refresh, so the Switch keeps its state.
    private fun toggleRow(list: LinearLayout, label: String, initial: Boolean,
                          onToggle: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ThemeTokens.SURFACE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(4), 0, dp(8)); layoutParams = lp
        }
        val tv = TextView(this).apply {
            text = label; textSize = 13f
            setTextColor(ThemeTokens.TEXT)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        @Suppress("UseSwitchCompatOrMaterialCode")
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }
        row.addView(tv)
        row.addView(sw)
        list.addView(row)
    }

    private fun btn(list: LinearLayout, label: String,
                    bg: Int = ThemeTokens.BUTTON, onClick: () -> Unit) {
        list.addView(Button(this).apply {
            text = label; isAllCaps = false; setBackgroundColor(bg)
            setTextColor(Color.WHITE); textSize = 13f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(6)); layoutParams = lp
            setOnClickListener { onClick() }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // No66-D41: the captaincy + crowding toggles below bind to in-memory
        // state that is only loaded from SharedPreferences by
        // CaptaincySkillEngine.init / CrowdingZoneDetector.init — and the
        // only caller of both is OverlayService (service start, after the
        // MediaProjection permission flow). This room opens straight from
        // the home cards, so without this the toggles read defaults
        // (captaincy OFF, crowding ON) instead of the persisted state, and
        // a toggle write before any init is silently lost (prefs is null).
        // init() is idempotent: it re-reads the same prefs OverlayService
        // reads, so calling it here changes nothing on the normal path.
        try { CaptaincySkillEngine.init(this) } catch (_: Throwable) {}
        try { CrowdingZoneDetector.init(this) } catch (_: Throwable) {}
        buildUi()
    }

    override fun onDestroy() {
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        summaryView = null; decisionGrid = null; decisionDetailView = null
        amplifierVerdictContainer = null; amplifierGrid = null
        crowdVerdictContainer = null; crowdGrid = null; busGrid = null
        logExecutor.shutdown()
        DashboardInjector.detach()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = page()

        val titleTv = TextView(this).apply {
            text = "🎮  GAMEPLAY ROOM"; textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(titleTv)

        val subTv = TextView(this).apply {
            text = "Live engine + contributor state · proof of effectiveness during gameplay"
            textSize = 10f; setTextColor(ThemeTokens.TEXT_MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(subTv)

        summaryView = TextView(this).apply {
            text = "Loading…"; textSize = 12f
            setTextColor(ThemeTokens.TEXT_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(summaryView)

        // ── DECISION LOOP ──────────────────────────────────────────────────
        section(root, "DECISION LOOP  (RuntimeDecisionLoop)")
        decisionGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(decisionGrid)
        decisionDetailView = detailCard(root, "Loading...")

        // ── POST-ARBITRATION AMPLIFIERS ───────────────────────────────────
        section(root, "POST-ARBITRATION AMPLIFIERS")
        amplifierVerdictContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(amplifierVerdictContainer)
        amplifierGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(amplifierGrid)

        // ── CROWDING ZONE ─────────────────────────────────────────────────
        section(root, "CROWDING ZONE DETECTOR")
        crowdVerdictContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(crowdVerdictContainer)
        crowdGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(crowdGrid)
        toggleRow(root, "Crowding Zone Detector enabled", CrowdingZoneDetector.isEnabled()) { checked ->
            CrowdingZoneDetector.setEnabled(checked)
            val msg = if (checked) "Crowding detector ENABLED" else "Crowding detector DISABLED"
            Toast.makeText(this@GameplayRoomActivity, msg, Toast.LENGTH_SHORT).show()
            logLine("CROWDING_TOGGLE: $msg")
            refreshNow()
        }

        // ── ADAPTER SIGNAL BUS ────────────────────────────────────────────
        section(root, "ADAPTER SIGNAL BUS  (cross-module state)")
        busGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(busGrid)

        // ── CAPTAINCY DESIGNATION ─────────────────────────────────────────
        section(root, "CAPTAINCY DESIGNATION  (Item 6)")
        val captaincyDescTv = TextView(this).apply {
            text = "Enable only when your squad captain holds the Captaincy skill\n" +
                   "(eFootball 2027: \"Reduces fatigue effects — entire squad\").\n" +
                   "Disabling clears the designation immediately."
            textSize = 10f; setTextColor(ThemeTokens.TEXT_MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, dp(6))
        }
        root.addView(captaincyDescTv)
        toggleRow(root, "Captain has Captaincy skill", CaptaincySkillEngine.isDesignated()) { checked ->
            CaptaincySkillEngine.setCaptainDesignated(checked)
            val msg = if (checked) "Captaincy designation ENABLED" else "Captaincy designation CLEARED"
            Toast.makeText(this@GameplayRoomActivity, msg, Toast.LENGTH_SHORT).show()
            logLine("CAPTAINCY_TOGGLE: $msg")
            refreshNow()
        }

        // ── DIAGNOSTIC ROOMS ────────────────────────────────────────────
        // Patch 3: CrashInspectorActivity, AnalyticsTheaterActivity and
        // DashboardInjector.attach() had zero launchers/callers repo-wide.
        // They are wired here, in the live diagnostics room, user-triggered.
        section(root, "DIAGNOSTIC ROOMS  (crash + analytics + live dashboard)")
        btn(root, "🔍  Crash Inspector") { startActivity(Intent(this, CrashInspectorActivity::class.java)) }
        btn(root, "📊  Analytics Theater") { startActivity(Intent(this, AnalyticsTheaterActivity::class.java)) }
        btn(root, "📈  Live Dashboard Overlay") { DashboardInjector.attach(this) }

        // ── CONTROLS ──────────────────────────────────────────────────────
        section(root, "CONTROLS")
        btn(root, "↺  Refresh Now") { refreshNow() }
        btn(root, "🗑  Clear Gameplay Log", ThemeTokens.DANGER) {
            logExecutor.execute {
                val msg = try {
                    LOG_FILE.writeText("")
                    "Gameplay log cleared"
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        btn(root, "←  Back", ThemeTokens.SURFACE) { finish() }

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

        startRefresh()
    }

    private fun startRefresh() {
        val r = object : Runnable {
            override fun run() {
                refreshNow()
                // no59 ITEM 4: STOPPED — the 2s refresh no longer re-arms.
                // InAppLiveDisplay is the single switch (default OFF):
                // with it off the room refreshes exactly once, when
                // startRefresh() posts it, and then only on the manual
                // "Refresh Now" button. Stopping the cycle also stops
                // the gameplaylogs.txt append in refreshNow() (logLine
                // runs once per manual refresh instead of every 2s).
                // File logging is untouched by this flag.
                if (com.assistant.diagnostic.InAppLiveDisplay.liveRefreshAllowed()) {
                    refreshHandler.postDelayed(this, 2_000L)
                }
            }
        }
        refreshRunnable = r
        refreshHandler.post(r)
    }

    private fun refreshNow() {
        try {
            val now = logFmt.format(Date())

            // ── Decision Loop snapshot ────────────────────────────────────
            val snap = RuntimeDecisionLoop.decisionRuntimeSnapshot()
            val decisions = (snap["decisions"] as? Number)?.toLong() ?: 0L
            val routed    = (snap["routed"] as? Number)?.toLong() ?: 0L
            val idle_unt  = (snap["idleUntrusted"] as? Number)?.toLong() ?: 0L
            val idle_none = (snap["idleNoContribution"] as? Number)?.toLong() ?: 0L
            val gate_blk  = (snap["gateBlocked"] as? Number)?.toLong() ?: 0L
            val stale_frm = (snap["staleFrame"] as? Number)?.toLong() ?: 0L
            val settings  = (snap["settingsExit"] as? Number)?.toLong() ?: 0L
            val route_rej = (snap["routeRejected"] as? Number)?.toLong() ?: 0L
            val choose_nl = (snap["chooseNull"] as? Number)?.toLong() ?: 0L
            val lastAct   = snap["lastAction"] ?: "none"
            val lastWt    = snap["lastWeight"] ?: 0f
            val updMs     = snap["lastUpdatedMs"] as? Long ?: 0L
            val ageMs     = System.currentTimeMillis() - updMs
            val freshness = when {
                updMs == 0L      -> "NEVER FIRED"
                ageMs < 2_000L   -> "LIVE (${ageMs}ms ago)"
                ageMs < 10_000L  -> "RECENT (${ageMs}ms ago)"
                else             -> "STALE (${ageMs}ms ago) — engine may be idle"
            }
            val routePct = if (decisions > 0L)
                String.format(Locale.US, "%.1f%%", routed * 100.0 / decisions) else "—"

            summaryView?.text = "Decisions $decisions · Routed $routed ($routePct) · $freshness"
            summaryView?.setTextColor(
                if (freshness.startsWith("STALE")) "#FF5252".toColorInt() else ThemeTokens.TEXT_MUTED)

            decisionGrid?.let { grid ->
                renderBulbGrid(grid, listOf(
                    BulbEntry("Decisions", if (decisions > 0L) BulbState.GREEN else BulbState.YELLOW, "$decisions"),
                    BulbEntry("Routed", if (routed > 0L) BulbState.GREEN else BulbState.YELLOW, "$routed ($routePct)"),
                    BulbEntry("Idle untrusted", if (idle_unt > 0L) BulbState.YELLOW else BulbState.GREEN, "$idle_unt"),
                    BulbEntry("Idle no-contrib", if (idle_none > 0L) BulbState.YELLOW else BulbState.GREEN, "$idle_none"),
                    BulbEntry("Gate blocked", if (gate_blk > 0L) BulbState.RED else BulbState.GREEN, "$gate_blk"),
                    BulbEntry("Stale frame", if (stale_frm > 0L) BulbState.RED else BulbState.GREEN, "$stale_frm"),
                    BulbEntry("Settings exits", if (settings > 0L) BulbState.YELLOW else BulbState.GREEN, "$settings"),
                    BulbEntry("Route rejected", if (route_rej > 0L) BulbState.RED else BulbState.GREEN, "$route_rej"),
                    BulbEntry("Choose null", if (choose_nl > 0L) BulbState.YELLOW else BulbState.GREEN, "$choose_nl")
                ))
            }
            // ── Decision mirror (root SmartAssistMetrics) ───────────────
            // Wired in patch 4: the root mirror counters were never
            // incremented and decisionRuntimeSnapshot() had no readers.
            val mirror = com.assistant.SmartAssistMetrics.decisionRuntimeSnapshot()
            decisionDetailView?.text =
                "Last action: $lastAct\n" +
                "Last weight: $lastWt\n" +
                "Freshness  : $freshness\n" +
                "ConAI meta : ${ConAIEngine.diagnostics()["metaDecisions"] ?: 0L} decisions / ${ConAIEngine.diagnostics()["metaOverrides"] ?: 0L} overrides\n" +
                "Mirror made  : ${mirror["decisionsMade"] ?: 0L}\n" +
                "Mirror avg ms: ${mirror["avgDecisionTimeMs"] ?: 0L}\n" +
                "Mirror last  : ${mirror["lastDecisionTimeMs"] ?: 0L}ms"

            // ── Amplifiers: Captaincy ───────────────────────────────────
            val capDiag  = CaptaincySkillEngine.diagnostics()
            val capDesig = capDiag["captainDesignated"] as? Boolean ?: false
            val capActive= capDiag["active"] as? Boolean ?: false
            val capActiv = capDiag["activations"] ?: 0
            val capFatigue = capDiag["lastFatigueProxy"] ?: 0f
            val capLift  = capDiag["lastTeamLift"] ?: 1f
            val capComp  = capDiag["lastComposureMs"] ?: 0L

            // Effectiveness verdict (same logic as the old room).
            val capVerdict = when {
                !capDesig                             -> "NO CAPTAIN DESIGNATED (toggle above)"
                !capActive && (capActiv as Long) == 0L -> "DESIGNATED but not yet firing"
                capActive                             -> "ACTIVE  lift=${capLift} +${capComp}ms"
                (capActiv as Long) > 0L               -> "FIRED ${capActiv}×  (idle now)"
                else                                  -> "INACTIVE"
            }
            val capState = when {
                capActive -> BulbState.GREEN
                ((capActiv as? Number)?.toLong() ?: 0L) > 0L -> BulbState.GREEN
                else -> BulbState.YELLOW
            }
            val capShort = when {
                capActive -> "ACTIVE"
                ((capActiv as? Number)?.toLong() ?: 0L) > 0L -> "FIRED"
                !capDesig -> "OFF"
                else -> "WAITING"
            }
            amplifierVerdictContainer?.let { container ->
                container.removeAllViews()
                container.addView(statusBox("Captaincy", capVerdict, capState, capShort,
                    "Designated=$capDesig active=$capActive activations=$capActiv"))
            }
            // Continuous outputs are live once the engine has fired at
            // least once (same counter convention as the dashboard:
            // counter > 0 = GREEN, else YELLOW).
            val capFired = ((capActiv as? Number)?.toLong() ?: 0L) > 0L
            amplifierGrid?.let { grid ->
                renderBulbGrid(grid, listOf(
                    BulbEntry("Designated", if (capDesig) BulbState.GREEN else BulbState.YELLOW, if (capDesig) "Yes" else "No"),
                    BulbEntry("Active", if (capActive) BulbState.GREEN else BulbState.YELLOW, if (capActive) "Yes" else "No"),
                    BulbEntry("Activations", if (capFired) BulbState.GREEN else BulbState.YELLOW, "$capActiv"),
                    BulbEntry("Fatigue proxy", if (capFired) BulbState.GREEN else BulbState.YELLOW, "$capFatigue"),
                    BulbEntry("Team lift", if (capFired) BulbState.GREEN else BulbState.YELLOW, "${capLift}×"),
                    BulbEntry("Added duration", if (capFired) BulbState.GREEN else BulbState.YELLOW, "+${capComp}ms")
                ))
            }

            // ── Crowding Zone ─────────────────────────────────────────────
            val crowdDiag = CrowdingZoneDetector.diagnostics()
            val inZone    = crowdDiag["inCrowdedZone"] as? Boolean ?: false
            val crowdLvl  = crowdDiag["crowdingLevel"] ?: 0f
            val crowdDet  = crowdDiag["detections"] ?: 0L
            val crowdVerdict = when {
                inZone    -> "IN CROWDED ZONE — duration capped 45ms"
                (crowdDet as Long) > 0L -> "Detected ${crowdDet}× (not now)"
                else      -> "NOT CROWDED"
            }
            val crowdState = when {
                inZone -> BulbState.RED
                (crowdDet as Long) > 0L -> BulbState.GREEN
                else -> BulbState.YELLOW
            }
            val crowdShort = if (inZone) "IN ZONE" else "CLEAR"
            crowdVerdictContainer?.let { container ->
                container.removeAllViews()
                container.addView(statusBox("Crowding zone", crowdVerdict, crowdState, crowdShort,
                    "Level=$crowdLvl detections=$crowdDet bus=${AdapterSignalBus.crowdingZone}"))
            }
            crowdGrid?.let { grid ->
                renderBulbGrid(grid, listOf(
                    BulbEntry("Level", crowdState, "$crowdLvl"),
                    BulbEntry("Detections", if ((crowdDet as Long) > 0L) BulbState.GREEN else BulbState.YELLOW, "$crowdDet"),
                    BulbEntry("Bus crowding zone", if (AdapterSignalBus.crowdingZone) BulbState.RED else BulbState.GREEN, "${AdapterSignalBus.crowdingZone}")
                ))
            }

            // ── Adapter Signal Bus ────────────────────────────────────────
            // Bulb colours from the live values. The mappings are the
            // ones the bus itself uses: environmentHostile is true when
            // netWindow == HOLD, lagVerdict == CHOKING, stutterState ==
            // SEIZURE or thermalStatus >= 3 (AdapterSignalBus.kt:136);
            // inputIsLagging is LAGGING / TOUCH_IRQ_STALL; memory is
            // CRITICAL / PRESSURE (isCritical / isUnderPressure).
            // thermalStatus is the Android thermal scale 0=NONE..6=
            // SHUTDOWN, -1 = missing (publishThermal coerces to -1..6).
            val netWin = AdapterSignalBus.netWindow
            val lagVerd = AdapterSignalBus.lagVerdict
            val stut = AdapterSignalBus.stutterState
            val therm = AdapterSignalBus.thermalStatus
            val memTier = AdapterSignalBus.memoryTier
            val memAvail = AdapterSignalBus.memoryAvailMb
            val inputCls = AdapterSignalBus.inputClassification
            val inputMs = AdapterSignalBus.inputLatencyMs
            val capThr = AdapterSignalBus.captureThrottle
            val execBrk = AdapterSignalBus.executionBrake
            val crowdZone = AdapterSignalBus.crowdingZone
            val crowdLvlBus = AdapterSignalBus.crowdingLevel
            val healCount = ConnectionHealEngine.diagnostics()["healCount"] ?: 0
            val congested = CongestionSentinelEngine.diagnostics()["congested"] ?: false
            val congestedSecs = CongestionSentinelEngine.diagnostics()["congestedForSecs"] ?: 0L
            val envHostile = AdapterSignalBus.environmentHostile
            busGrid?.let { grid ->
                renderBulbGrid(grid, listOf(
                    BulbEntry("Net", when (netWin) {
                        "GO" -> BulbState.GREEN
                        "HOLD" -> BulbState.RED
                        else -> BulbState.YELLOW
                    }, netWin),
                    BulbEntry("Lag", when (lagVerd) {
                        "SMOOTH" -> BulbState.GREEN
                        "CHOKING" -> BulbState.RED
                        else -> BulbState.YELLOW
                    }, lagVerd),
                    BulbEntry("Stutter", when (stut) {
                        "CALM" -> BulbState.GREEN
                        "SEIZURE" -> BulbState.RED
                        else -> BulbState.YELLOW
                    }, stut),
                    BulbEntry("Thermal", when {
                        therm < 0 -> BulbState.YELLOW
                        therm <= 1 -> BulbState.GREEN
                        therm == 2 -> BulbState.YELLOW
                        else -> BulbState.RED
                    }, "$therm"),
                    BulbEntry("Memory", when (memTier) {
                        "CRITICAL" -> BulbState.RED
                        "PRESSURE" -> BulbState.YELLOW
                        else -> BulbState.GREEN
                    }, "$memTier (${memAvail}MB)"),
                    BulbEntry("Input", if (inputCls == "LAGGING" || inputCls == "TOUCH_IRQ_STALL") BulbState.RED else BulbState.GREEN, "$inputCls (${inputMs}ms)"),
                    BulbEntry("Capture throttle", if (capThr == 0) BulbState.GREEN else BulbState.YELLOW, "$capThr"),
                    BulbEntry("Exec brake", if (execBrk == 0) BulbState.GREEN else BulbState.YELLOW, "$execBrk"),
                    BulbEntry("Crowding zone", if (crowdZone) BulbState.RED else BulbState.GREEN, "$crowdZone ($crowdLvlBus)"),
                    BulbEntry("Net heals", if (((healCount as? Number)?.toLong() ?: 0L) > 0L) BulbState.GREEN else BulbState.YELLOW, "$healCount heals"),
                    BulbEntry("Congested", if ((congested as? Boolean) == true) BulbState.RED else BulbState.GREEN, "$congested ${congestedSecs}s"),
                    BulbEntry("Env hostile", if (envHostile) BulbState.RED else BulbState.GREEN, "$envHostile")
                ))
            }

            // ── Log to file ───────────────────────────────────────────────
            logLine(buildLogEntry(now, decisions, routed, routePct, lastAct,
                capDesig, capActive, capLift, capComp,
                inZone, crowdLvl))

        } catch (t: Throwable) {
            // No66-D43: this used to swallow every failure silently, so a
            // snapshot failure left "Loading..." on screen forever with no
            // trace anywhere — the opposite of what a truth room is for.
            // Log it and say so on screen; the next manual refresh retries.
            try { android.util.Log.w("GameplayRoom", "refreshNow failed", t) } catch (_: Throwable) {}
            try { summaryView?.text = "Refresh failed: ${t.message ?: t.javaClass.simpleName}" } catch (_: Throwable) {}
        }
    }

    private fun buildLogEntry(
        ts: String,
        decisions: Any, routed: Any, routePct: String, lastAct: Any,
        capDesig: Boolean, capActive: Boolean, capLift: Any, capComp: Any,
        inZone: Boolean, crowdLvl: Any
    ): String {
        val frameSnap = try {
            // No66-D42: this was `RuntimeDecisionLoop.let { ... }` — a dead
            // `let`: the receiver was never used inside the block. Direct call.
            com.assistant.FrameAssembler.frameRuntimeSnapshot()
        } catch (_: Throwable) {
            emptyMap<String, Any>()
        }

        val decisionSnap = try {
            RuntimeDecisionLoop.decisionRuntimeSnapshot()
        } catch (_: Throwable) {
            emptyMap<String, Any>()
        }

        val frameId = frameSnap["frameId"] ?: -1L
        val frames = frameSnap["frames"] ?: 0L
        val decisionCount = decisionSnap["decisions"] ?: 0L
        val decisionRouted = decisionSnap["routed"] ?: 0L

        return "[$ts] dec=$decisions routed=$routed($routePct) act=$lastAct | " +
               "fs=MODEL_ONLY(no gameplay input) | " +
               "cap=${if (!capDesig) "noDesig" else if (capActive) "ON(lift=$capLift +${capComp}ms)" else "designatedOff"} | " +
               "crowd=${if (inZone) "ZONE(lv=$crowdLvl)" else "clear"} | " +
               "lag=${AdapterSignalBus.lagVerdict} stutter=${AdapterSignalBus.stutterState} | " +
               "capture(frameId=$frameId frames=$frames) decisionLoop(decisions=$decisionCount routed=$decisionRouted)"
    }

    private val logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun logLine(line: String) {
        logExecutor.execute {
            try {
                val f = LOG_FILE
                f.parentFile?.mkdirs()
                PrintWriter(FileWriter(f, true)).use { it.println(line) }
            } catch (_: Throwable) {}
        }
    }
}

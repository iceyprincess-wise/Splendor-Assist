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
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.assistant.adapter.smartassist.CaptaincySkillEngine
import com.assistant.adapter.smartassist.CrowdingZoneDetector
import com.assistant.adapter.smartassist.FightingSpiritEngine
import com.assistant.adapter.smartassist.RuntimeDecisionLoop
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gameplay Room (Items 4 + 6)
 *
 * The raw truth room for every gameplay engine and contributor in
 * adapter_smartassist. Shows live state, request queue, expiry,
 * effectiveness, and whether each engine/amplifier is working or dead.
 *
 * Also exposes the Captaincy designation toggle (Item 6 integration spec):
 *   CaptaincySkillEngine.setCaptainDesignated(true/false)
 *
 * Logs to /sdcard/Splendor-Assist/gameplaylogs.txt on every refresh cycle.
 */
class GameplayRoomActivity : AppCompatActivity() {

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    private var decisionView: TextView? = null
    private var amplifierView: TextView? = null
    private var busView: TextView? = null
    private var crowdView: TextView? = null
    private var captaincyToggle: Switch? = null

    private val logFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val LOG_FILE: File
        get() = SplendorStorageRoot.file("gameplaylogs.txt")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(20), dp(14), dp(40))
        setBackgroundColor(Color.parseColor("#080816"))
    }

    private fun section(list: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title; textSize = 13f
            setTextColor(Color.parseColor("#4FC3F7"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        list.addView(tv)
        list.addView(android.view.View(this).apply {
            setBackgroundColor(Color.parseColor("#1A2A4A"))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.setMargins(0, 0, 0, dp(6)); layoutParams = lp
        })
    }

    private fun mono(list: LinearLayout, initText: String = ""): TextView {
        val tv = TextView(this).apply {
            text = initText; textSize = 10f
            setTextColor(Color.parseColor("#CCDDEE"))
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, dp(6))
        }
        list.addView(tv)
        return tv
    }

    private fun btn(list: LinearLayout, label: String,
                    bg: Int = Color.parseColor("#1565C0"), onClick: () -> Unit) {
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
        buildUi()
    }

    override fun onDestroy() {
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        decisionView = null; amplifierView = null; busView = null; crowdView = null
        super.onDestroy()
    }

    private fun buildUi() {
        val root = page()

        val titleTv = TextView(this).apply {
            text = "🎮  GAMEPLAY ROOM"; textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(titleTv)

        val subTv = TextView(this).apply {
            text = "Live engine + contributor state · proof of effectiveness during gameplay"
            textSize = 10f; setTextColor(Color.parseColor("#6688AA"))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(subTv)

        // ── DECISION LOOP ──────────────────────────────────────────────────
        section(root, "DECISION LOOP  (RuntimeDecisionLoop)")
        decisionView = mono(root, "Loading...")

        // ── POST-ARBITRATION AMPLIFIERS ───────────────────────────────────
        section(root, "POST-ARBITRATION AMPLIFIERS")
        amplifierView = mono(root, "Loading...")

        // ── CROWDING ZONE ─────────────────────────────────────────────────
        section(root, "CROWDING ZONE DETECTOR")
        crowdView = mono(root, "Loading...")

        // ── ADAPTER SIGNAL BUS ────────────────────────────────────────────
        section(root, "ADAPTER SIGNAL BUS  (cross-module state)")
        busView = mono(root, "Loading...")

        // ── CAPTAINCY DESIGNATION ─────────────────────────────────────────
        section(root, "CAPTAINCY DESIGNATION  (Item 6)")
        val captaincyDescTv = TextView(this).apply {
            text = "Enable only when your squad captain holds the Captaincy skill\n" +
                   "(eFootball 2027: \"Reduces fatigue effects — entire squad\").\n" +
                   "Disabling clears the designation immediately."
            textSize = 10f; setTextColor(Color.parseColor("#AABBCC"))
            setPadding(0, dp(2), 0, dp(6))
        }
        root.addView(captaincyDescTv)

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(4), 0, dp(8)); layoutParams = lp
        }
        val toggleLabel = TextView(this).apply {
            text = "Captain has Captaincy skill"; textSize = 13f
            setTextColor(Color.parseColor("#DDDDDD"))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        @Suppress("UseSwitchCompatOrMaterialCode")
        val sw = Switch(this).apply {
            isChecked = CaptaincySkillEngine.isDesignated() // V6 FIX: bind to REAL persisted state
            setOnCheckedChangeListener { _, checked ->
                CaptaincySkillEngine.setCaptainDesignated(checked)
                val msg = if (checked) "Captaincy designation ENABLED" else "Captaincy designation CLEARED"
                android.widget.Toast.makeText(this@GameplayRoomActivity, msg,
                    android.widget.Toast.LENGTH_SHORT).show()
                logLine("CAPTAINCY_TOGGLE: $msg")
            }
        }
        captaincyToggle = sw
        toggleRow.addView(toggleLabel)
        toggleRow.addView(sw)
        root.addView(toggleRow)

        // ── CONTROLS ──────────────────────────────────────────────────────
        section(root, "CONTROLS")
        btn(root, "↺  Refresh Now") { refreshNow() }
        btn(root, "🗑  Clear Gameplay Log", Color.parseColor("#8B0000")) {
            try {
                LOG_FILE.writeText("")
                android.widget.Toast.makeText(this, "Gameplay log cleared",
                    android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Error: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        btn(root, "←  Back", Color.parseColor("#222233")) { finish() }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080816"))
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        startRefresh()
    }

    private fun startRefresh() {
        val r = object : Runnable {
            override fun run() {
                refreshNow()
                refreshHandler.postDelayed(this, 2_000L)
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
            val idle_unt  = snap["idleUntrusted"] ?: 0
            val idle_none = snap["idleNoContribution"] ?: 0
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
                String.format("%.1f%%", routed * 100.0 / decisions) else "—"

            val decTxt =
                "Decisions  : $decisions\n" +
                "Routed     : $routed ($routePct)\n" +
                "Idle/untrust: $idle_unt    Idle/noContrib: $idle_none\n" +
                "Last action: $lastAct\n" +
                "Last weight: $lastWt\n" +
                "Freshness  : $freshness"
            decisionView?.text = decTxt

            // ── Amplifiers: FightingSpirit + Captaincy ───────────────────
            val fsDiag = FightingSpiritEngine.diagnostics()
            val fsActive = fsDiag["active"] as? Boolean ?: false
            val fsActiv  = fsDiag["activations"] ?: 0
            val fsRet    = fsDiag["lastRetention"] ?: 1f
            val fsPres   = fsDiag["lastPressure"] ?: 0f

            val capDiag  = CaptaincySkillEngine.diagnostics()
            val capDesig = capDiag["captainDesignated"] as? Boolean ?: false
            val capActive= capDiag["active"] as? Boolean ?: false
            val capActiv = capDiag["activations"] ?: 0
            val capFatigue = capDiag["lastFatigueProxy"] ?: 0f
            val capLift  = capDiag["lastTeamLift"] ?: 1f
            val capComp  = capDiag["lastComposureMs"] ?: 0L

            // Effectiveness verdict
            val fsVerdict = when {
                !fsActive && (fsActiv as Long) == 0L -> "⬜ NOT YET FIRED"
                fsActive                             -> "🟢 ACTIVE  (retention=${fsRet})"
                (fsActiv as Long) > 0L               -> "🔵 FIRED ${fsActiv}×  (idle now)"
                else                                 -> "⬜ INACTIVE"
            }
            val capVerdict = when {
                !capDesig                             -> "⬜ NO CAPTAIN DESIGNATED (toggle above)"
                !capActive && (capActiv as Long) == 0L -> "🟡 DESIGNATED but not yet firing"
                capActive                             -> "🟢 ACTIVE  lift=${capLift} +${capComp}ms"
                (capActiv as Long) > 0L               -> "🔵 FIRED ${capActiv}×  (idle now)"
                else                                  -> "⬜ INACTIVE"
            }

            val ampTxt =
                "── FightingSpiritEngine ──\n" +
                "  Status     : $fsVerdict\n" +
                "  Activations: ${fsActiv}   Pressure: ${fsPres}\n" +
                "  Accuracy retention: ${fsRet}  (1.0=none, 1.20=max)\n\n" +
                "── CaptaincySkillEngine ──\n" +
                "  Status     : $capVerdict\n" +
                "  Designated : $capDesig\n" +
                "  Activations: ${capActiv}   Fatigue proxy: ${capFatigue}\n" +
                "  Team lift  : ${capLift}×   Composure: +${capComp}ms"
            amplifierView?.text = ampTxt

            // ── Crowding Zone ─────────────────────────────────────────────
            val crowdDiag = CrowdingZoneDetector.diagnostics()
            val inZone    = crowdDiag["inCrowdedZone"] as? Boolean ?: false
            val crowdLvl  = crowdDiag["crowdingLevel"] ?: 0f
            val crowdDet  = crowdDiag["detections"] ?: 0L
            val crowdVerdict = when {
                inZone    -> "🔴 IN CROWDED ZONE — duration capped 45ms"
                (crowdDet as Long) > 0L -> "🔵 Detected ${crowdDet}× (not now)"
                else      -> "⬜ NOT CROWDED"
            }
            val crowdTxt =
                "Status     : $crowdVerdict\n" +
                "Level      : $crowdLvl   Detections: $crowdDet\n" +
                "Bus.crowdingZone : ${AdapterSignalBus.crowdingZone}"
            crowdView?.text = crowdTxt

            // ── Adapter Signal Bus ────────────────────────────────────────
            val busTxt =
                "net=${AdapterSignalBus.netWindow}  lag=${AdapterSignalBus.lagVerdict}\n" +
                "stutter=${AdapterSignalBus.stutterState}  thermal=${AdapterSignalBus.thermalStatus}\n" +
                "memory=${AdapterSignalBus.memoryTier}(${AdapterSignalBus.memoryAvailMb}MB)\n" +
                "input=${AdapterSignalBus.inputClassification}(${AdapterSignalBus.inputLatencyMs}ms)\n" +
                "captureThrottle=${AdapterSignalBus.captureThrottle}  execBrake=${AdapterSignalBus.executionBrake}\n" +
                "crowdingZone=${AdapterSignalBus.crowdingZone}(${AdapterSignalBus.crowdingLevel})\n" +
                "envHostile=${AdapterSignalBus.environmentHostile}"
            busView?.text = busTxt

            // ── Log to file ───────────────────────────────────────────────
            logLine(buildLogEntry(now, decisions, routed, routePct, lastAct,
                fsActive, fsRet, fsPres, capDesig, capActive, capLift, capComp,
                inZone, crowdLvl))

        } catch (_: Throwable) {}
    }

    private fun buildLogEntry(
        ts: String,
        decisions: Any, routed: Any, routePct: String, lastAct: Any,
        fsActive: Boolean, fsRet: Any, fsPres: Any,
        capDesig: Boolean, capActive: Boolean, capLift: Any, capComp: Any,
        inZone: Boolean, crowdLvl: Any
    ): String {
        val frameSnap = try {
            RuntimeDecisionLoop.let {
                com.assistant.adapter.smartassist.FrameAssembler.frameRuntimeSnapshot()
            }
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
               "fs=${if (fsActive) "ON" else "off"}(ret=$fsRet pres=$fsPres) | " +
               "cap=${if (!capDesig) "noDesig" else if (capActive) "ON(lift=$capLift +${capComp}ms)" else "designatedOff"} | " +
               "crowd=${if (inZone) "ZONE(lv=$crowdLvl)" else "clear"} | " +
               "lag=${AdapterSignalBus.lagVerdict} stutter=${AdapterSignalBus.stutterState} | " +
               "capture(frameId=$frameId frames=$frames) decisionLoop(decisions=$decisionCount routed=$decisionRouted)"
    }

    private fun logLine(line: String) {
        try {
            val f = LOG_FILE
            f.parentFile?.mkdirs()
            PrintWriter(FileWriter(f, true)).use { it.println(line) }
        } catch (_: Throwable) {}
    }
}

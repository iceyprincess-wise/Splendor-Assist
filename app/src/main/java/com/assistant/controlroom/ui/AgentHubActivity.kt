package com.assistant.controlroom.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
import com.assistant.InAppAgentCore
import com.assistant.RuntimeSelfHealEngine
import com.assistant.diagnostic.InAppLiveDisplay

/**
 * Agent Hub Room (Item 4/6) — NEW UI (Nov 2026).
 *
 * The single authoritative Activity for the AI Self-Heal Agent monitor.
 * Opened by cardAccessibility and cardNotifications on the home screen,
 * and linked from FutureRooms (No66-D44: FutureRooms' inline agent screen
 * was a duplicate of this room and is removed — this is the only agent
 * monitor; its "Force Heal Cycle Now" button genuinely forces a cycle
 * via InAppAgentCore.runNow()).
 *
 * New design (same language as the dashboard bulbs + Diagnosis Room):
 *   - Centred header + summary line.
 *   - LIVE STATUS as a 2-column bulb grid (dot + label + live value in
 *     each box), parsed from RuntimeSelfHealEngine.getStatusSummary() —
 *     the same source the old mono dump read, so every value is truth,
 *     never hardcoded.
 *   - AGENT CORE as status rows from InAppAgentCore.snapshot().
 *   - HEAL EVENT LOG (latest 20) as severity-coloured cards: severity
 *     dot + label, category, timestamp, DETECTED and FIX text. No logcat
 *     dump, no file writes, no heavy work on the UI thread.
 *
 * Refresh: the 3s tick re-arms only when InAppLiveDisplay allows it
 * (no59 ITEM 4: with live refresh off the room refreshes once on open);
 * the manual "Refresh Now" button refreshes on demand either way.
 * Sections whose input did not change are not rebuilt, so the heal log
 * (20 cards) is only re-rendered when a new event actually arrives —
 * this room must never become a bottleneck.
 */
class AgentHubActivity : AppCompatActivity() {

    private enum class BulbState { GREEN, YELLOW, RED }

    private data class BulbEntry(val label: String, val state: BulbState, val value: String)

    // Parsed form of RuntimeSelfHealEngine.getStatusSummary(). Every
    // field is nullable: a missing token renders as "—", never a crash.
    private data class StatusData(
        val agentStatus: String?, val heals: String?, val age: String?,
        val fg: String?, val fgForces: String?, val shed: String?,
        val dispatch: String?, val net: String?, val lag: String?,
        val stutter: String?, val mem: String?, val thermal: String?,
        val battery: String?, val decisions: String?, val routed: String?,
        val captureRestarts: String?, val lastAction: String?
    )

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    private var summaryView: TextView? = null
    private var statusGrid: LinearLayout? = null
    private var statusFootView: TextView? = null
    private var coreContainer: LinearLayout? = null
    private var eventsContainer: LinearLayout? = null

    // Change keys: rebuild a section only when its input changed.
    private var lastCoreKey: String? = null
    private var lastEventsKey: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bulbColor(state: BulbState): Int = when (state) {
        BulbState.GREEN -> "#00E676".toColorInt()
        BulbState.YELLOW -> "#FFC107".toColorInt()
        BulbState.RED -> "#FF5252".toColorInt()
    }

    private fun severityColor(severity: String): Int = when (severity) {
        "FIXED" -> ThemeTokens.SUCCESS
        "CRITICAL", "CODE_FIX_NEEDED" -> "#FF5252".toColorInt()
        "WARNING" -> ThemeTokens.WARN
        else -> ThemeTokens.TEXT_MUTED
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(24), dp(16), dp(32))
        setBackgroundColor(ThemeTokens.BG)
    }

    private fun divider(list: LinearLayout) {
        list.addView(View(this).apply {
            setBackgroundColor(ThemeTokens.SURFACE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.setMargins(0, dp(14), 0, dp(14)); layoutParams = lp
        })
    }

    // Section header, centred like the dashboard banners, with a
    // divider under it (Diagnosis Room pattern).
    private fun sectionTitle(list: LinearLayout, title: String) {
        list.addView(TextView(this).apply {
            text = title; textSize = 13f
            setTextColor(ThemeTokens.ACCENT_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(4), 0, dp(4))
        })
        list.addView(View(this).apply {
            setBackgroundColor(ThemeTokens.SURFACE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.setMargins(0, 0, 0, dp(8)); layoutParams = lp
        })
    }

    // Dashboard bulb cell (MainActivity.buildBulbCell), centred:
    // dot + bold label row, live value under it, panel_surface box.
    private fun bulbCell(entry: BulbEntry): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        top.addView(TextView(this).apply {
            text = "●"; textSize = 14f
            setTextColor(bulbColor(entry.state))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(6) }
        })
        top.addView(TextView(this).apply {
            text = entry.label; textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        // Centre the dot+label row inside the cell: wrap it in a
        // full-width row whose gravity centres its child.
        val topWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        topWrap.addView(top)
        cell.addView(topWrap)
        cell.addView(TextView(this).apply {
            text = entry.value; textSize = 11f
            setTextColor(ThemeTokens.TEXT_MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(2) }
        })
        return cell
    }

    private fun renderBulbGrid(container: LinearLayout?, entries: List<BulbEntry>) {
        val c = container ?: return
        c.removeAllViews()
        entries.chunked(2).forEach { row ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(4); bottomMargin = dp(4) }
            }
            row.forEach { entry -> rowLayout.addView(bulbCell(entry)) }
            if (row.size == 1) {
                rowLayout.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            c.addView(rowLayout)
        }
    }

    // Diagnosis Room style box row: status dot + bold name + wrapping
    // muted value. Used for AGENT CORE and the full-width Last action.
    private fun infoRow(container: LinearLayout?, name: String, value: String, state: BulbState) {
        val c = container ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ThemeTokens.SURFACE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(6)); layoutParams = lp
        }
        row.addView(TextView(this).apply {
            text = "●"; textSize = 14f
            setTextColor(bulbColor(state))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) }
        })
        row.addView(TextView(this).apply {
            text = name; textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) }
        })
        row.addView(TextView(this).apply {
            text = value; textSize = 11f
            setTextColor(ThemeTokens.TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        c.addView(row)
    }

    // One heal event as an advanced card: severity dot + label,
    // category, timestamp, then DETECTED and FIX lines.
    private fun eventCard(
        container: LinearLayout?,
        ev: RuntimeSelfHealEngine.HealEvent
    ) {
        val c = container ?: return
        val sev = severityColor(ev.severity)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.assistant.overlay.R.drawable.panel_surface)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(8)); layoutParams = lp
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        head.addView(TextView(this).apply {
            text = "●"; textSize = 13f
            setTextColor(sev)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(6) }
        })
        head.addView(TextView(this).apply {
            text = ev.severity; textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(sev)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) }
        })
        head.addView(TextView(this).apply {
            text = ev.category; textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = ev.timestamp; textSize = 10f
            setTextColor(ThemeTokens.TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        card.addView(head)
        fun labelledLine(label: String, labelColor: Int, value: String) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(4) }
            }
            line.addView(TextView(this).apply {
                text = label; textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(labelColor)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6) }
            })
            line.addView(TextView(this).apply {
                text = value; textSize = 11f
                setTextColor(ThemeTokens.TEXT)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            card.addView(line)
        }
        // No66-D46: cap long event text — an uncapped DETECTED/FIX
        // string builds an unbounded TextView.
        labelledLine("DETECTED", ThemeTokens.WARN, ev.detected.take(160))
        labelledLine("FIX", ThemeTokens.SUCCESS, ev.fix.take(160))
        c.addView(card)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI-2: edge-to-edge (targetSdk 36 enforces it) — draw behind
        // the system bars; the insets are applied to the root ScrollView
        // below so no box sits under the status / navigation bars.
        enableEdgeToEdge()
        buildUi()
    }

    override fun onDestroy() {
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        summaryView = null
        statusGrid = null
        statusFootView = null
        coreContainer = null
        eventsContainer = null
        super.onDestroy()
    }

    private fun buildUi() {
        val root = page()

        root.addView(TextView(this).apply {
            text = "🤖  AGENT HUB ROOM"; textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "AI Self-Heal Agent — runtime monitor for broken engine states"
            textSize = 11f; setTextColor(ThemeTokens.TEXT_MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(4))
        })
        summaryView = TextView(this).apply {
            text = "Loading agent status…"; textSize = 12f
            setTextColor(ThemeTokens.TEXT_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(summaryView)

        divider(root)

        sectionTitle(root, "LIVE STATUS")
        statusGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(statusGrid)
        statusFootView = TextView(this).apply {
            text = ""; textSize = 10f
            setTextColor(ThemeTokens.TEXT_DIM)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(statusFootView)

        divider(root)

        sectionTitle(root, "AGENT CORE")
        coreContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(coreContainer)

        divider(root)

        sectionTitle(root, "HEAL EVENT LOG  (latest 20)")
        eventsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(eventsContainer)

        divider(root)

        root.addView(Button(this).apply {
            text = "↺  Refresh Now"; isAllCaps = false
            setBackgroundColor(ThemeTokens.BUTTON)
            setTextColor(Color.WHITE); textSize = 14f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(6)); layoutParams = lp
            setOnClickListener { refreshOnce() }
        })

        root.addView(Button(this).apply {
            text = "↺  Force Heal Cycle Now"; isAllCaps = false
            setBackgroundColor(ThemeTokens.BUTTON_ALT)
            // No66-D23: white text on BUTTON_ALT was 2.38:1; alt buttons
            // (now brand cyan #00E5FF) take dark text — 12.79:1.
            setTextColor(ThemeTokens.BG); textSize = 14f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(6)); layoutParams = lp
            setOnClickListener {
                try {
                    InAppAgentCore.runNow()
                    android.widget.Toast.makeText(this@AgentHubActivity, "Agent cycle triggered",
                        android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@AgentHubActivity, "Error: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "←  Back"; isAllCaps = false
            setBackgroundColor(ThemeTokens.BUTTON_NEUTRAL)
            setTextColor(Color.WHITE); textSize = 14f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(6)); layoutParams = lp
            setOnClickListener { finish() }
        })

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

    // Parse getStatusSummary() into fields. Format (one source, the
    // same string the old mono dump showed):
    //   STATUS: MONITORING  |  heals: 0  |  age: 84s
    //   fg=false  shed=NONE  dispatch=0%  fgForces=0
    //   net=GO  lag=SMOOTH
    //   stutter=CALM  mem=PRESSURE
    //   thermal=0  battery=25%chg=true
    //   decisions=413  routed=0  captureRestarts=0
    //   lastAction=idle-no-contribution
    //   HealLog: /sdcard/Splendor-Assist/SplendorHealLog.txt
    // Returns null when the string carries no STATUS line (e.g. the
    // engine returned an error) — the caller shows an error row then.
    private fun parseStatus(summary: String): StatusData? {
        val kv = HashMap<String, String>()
        var agentStatus: String? = null
        var heals: String? = null
        var age: String? = null
        for (line in summary.lines()) {
            val t = line.trim()
            if (t.startsWith("STATUS:")) {
                val m = Regex("""STATUS:\s*(\S+)\s*\|\s*heals:\s*(\d+)\s*\|\s*age:\s*(\d+)s""").find(t)
                if (m != null) {
                    agentStatus = m.groupValues[1]
                    heals = m.groupValues[2]
                    age = m.groupValues[3]
                }
            } else {
                for (tok in t.split(Regex("\\s+"))) {
                    val eq = tok.indexOf('=')
                    if (eq > 0) kv[tok.substring(0, eq)] = tok.substring(eq + 1)
                }
            }
        }
        if (agentStatus == null) return null
        return StatusData(
            agentStatus = agentStatus, heals = heals, age = age,
            fg = kv["fg"], fgForces = kv["fgForces"], shed = kv["shed"],
            dispatch = kv["dispatch"], net = kv["net"], lag = kv["lag"],
            stutter = kv["stutter"], mem = kv["mem"], thermal = kv["thermal"],
            battery = kv["battery"], decisions = kv["decisions"],
            routed = kv["routed"], captureRestarts = kv["captureRestarts"],
            lastAction = kv["lastAction"]
        )
    }

    // "25%chg=true" -> "25% · charging" / "25% · on battery".
    private fun batteryText(raw: String?): Pair<String, Boolean?> {
        if (raw == null) return "—" to null
        val parts = raw.split("%chg=")
        if (parts.size != 2) return raw to null
        val charging = parts[1] == "true"
        return "${parts[0]}% · ${if (charging) "charging" else "on battery"}" to charging
    }

    private fun refreshOnce() {
        try {
            val summary = RuntimeSelfHealEngine.getStatusSummary()
            val data = parseStatus(summary)
            val events = RuntimeSelfHealEngine.healEvents
            if (data == null) {
                summaryView?.text = "Agent status unavailable"
                statusGrid?.removeAllViews()
                infoRow(statusGrid, "Status", summary.take(160), BulbState.RED)
                statusFootView?.text = ""
            } else {
                summaryView?.text =
                    "${data.agentStatus} · ${data.heals ?: "?"} heals · ${events.size} events"
                renderStatusBulbs(data)
                statusFootView?.text = "HealLog: /sdcard/Splendor-Assist/SplendorHealLog.txt"
            }

            val core = InAppAgentCore.snapshot()
            val coreKey = core.toString()
            if (coreKey != lastCoreKey) {
                lastCoreKey = coreKey
                renderCore(core)
            }

            val eventsKey = "${events.size}:${events.lastOrNull()?.timestamp ?: ""}:${events.lastOrNull()?.category ?: ""}"
            if (eventsKey != lastEventsKey) {
                lastEventsKey = eventsKey
                renderEvents(events)
            }
        } catch (_: Throwable) {}
    }

    private fun renderStatusBulbs(data: StatusData) {
        val st = data.agentStatus ?: "—"
        val heals = data.heals?.toLongOrNull()
        val disp = data.dispatch?.trimEnd('%')?.toLongOrNull()
        val dec = data.decisions?.toLongOrNull()
        val routed = data.routed?.toLongOrNull()
        val restarts = data.captureRestarts?.toLongOrNull()
        val fgForces = data.fgForces?.toLongOrNull()
        val (batteryValue, batteryCharging) = batteryText(data.battery)
        val bulbs = listOf(
            BulbEntry("Status", when (st) {
                "MONITORING" -> BulbState.GREEN
                else -> BulbState.YELLOW
            }, st),
            BulbEntry("Heals", if ((heals ?: 0L) > 0) BulbState.GREEN else BulbState.YELLOW,
                "${heals ?: "—"}"),
            BulbEntry("Age", if (st == "MONITORING") BulbState.GREEN else BulbState.YELLOW,
                if (data.age != null) "${data.age}s" else "—"),
            BulbEntry("Foreground", if (data.fg == "true") BulbState.GREEN else BulbState.YELLOW,
                if (data.fg == "true") "In game" else "Background"),
            BulbEntry("FG forces", if ((fgForces ?: 0L) == 0L) BulbState.GREEN else BulbState.YELLOW,
                "${fgForces ?: "—"}"),
            BulbEntry("Load shed", when (data.shed) {
                "NONE" -> BulbState.GREEN
                "LIGHT" -> BulbState.YELLOW
                "HEAVY" -> BulbState.RED
                else -> BulbState.YELLOW
            }, data.shed ?: "—"),
            BulbEntry("Dispatch", if ((disp ?: 0L) > 0) BulbState.GREEN else BulbState.YELLOW,
                data.dispatch ?: "—"),
            BulbEntry("Network", if (data.net == "GO") BulbState.GREEN else BulbState.YELLOW,
                data.net ?: "—"),
            BulbEntry("Lag", if (data.lag == "SMOOTH") BulbState.GREEN else BulbState.YELLOW,
                data.lag ?: "—"),
            BulbEntry("Stutter", if (data.stutter == "CALM") BulbState.GREEN else BulbState.YELLOW,
                data.stutter ?: "—"),
            BulbEntry("Memory", when (data.mem) {
                "NORMAL" -> BulbState.GREEN
                "PRESSURE" -> BulbState.YELLOW
                "CRITICAL" -> BulbState.RED
                else -> BulbState.YELLOW
            }, data.mem ?: "—"),
            BulbEntry("Thermal", if (data.thermal == "0") BulbState.GREEN else BulbState.YELLOW,
                data.thermal ?: "—"),
            BulbEntry("Battery", if (batteryCharging == true) BulbState.GREEN else BulbState.YELLOW,
                batteryValue),
            BulbEntry("Decisions", if ((dec ?: 0L) > 0) BulbState.GREEN else BulbState.YELLOW,
                "${dec ?: "—"}"),
            BulbEntry("Routed", if ((routed ?: 0L) > 0) BulbState.GREEN else BulbState.YELLOW,
                "${routed ?: "—"}"),
            BulbEntry("Capture restarts", if ((restarts ?: 0L) == 0L) BulbState.GREEN else BulbState.YELLOW,
                "${restarts ?: "—"}")
        )
        renderBulbGrid(statusGrid, bulbs)
        // Last action is a long free string — full-width row, not a bulb.
        infoRow(statusGrid, "Last action", data.lastAction ?: "—", BulbState.YELLOW)
    }

    private fun renderCore(core: com.assistant.InAppAgentSnapshot) {
        val c = coreContainer ?: return
        c.removeAllViews()
        infoRow(c, "Running", if (core.running) "Running" else "Stopped",
            if (core.running) BulbState.GREEN else BulbState.RED)
        infoRow(c, "Cycles", "${core.cycles}",
            if (core.cycles > 0) BulbState.GREEN else BulbState.YELLOW)
        // Phase I (I-6): lastObservationMs was written by snapshot()
        // and read by nobody — its first live reader. Wall clock:
        // RuntimeObservation timestampMs is System.currentTimeMillis().
        val lastObs = if (core.lastObservationMs > 0L)
            "${System.currentTimeMillis() - core.lastObservationMs}ms ago" else "—"
        infoRow(c, "Last obs", lastObs,
            if (core.lastObservationMs > 0L) BulbState.GREEN else BulbState.YELLOW)
        infoRow(c, "Last action", core.lastAction.ifBlank { "—" }, BulbState.YELLOW)
        infoRow(c, "Reason", core.lastReason.ifBlank { "—" }, BulbState.YELLOW)
        infoRow(c, "Verified", if (core.lastVerified) "Verified" else "Not verified",
            if (core.lastVerified) BulbState.GREEN else BulbState.YELLOW)
        infoRow(c, "Verifier", core.lastVerification.ifBlank { "—" }, BulbState.YELLOW)
    }

    private fun renderEvents(events: List<RuntimeSelfHealEngine.HealEvent>) {
        val c = eventsContainer ?: return
        c.removeAllViews()
        if (events.isEmpty()) {
            c.addView(TextView(this).apply {
                text = "No heal events yet.\nAgent is monitoring...\n\nIf the game is running and no events\nappear after 30s, the agent found no problems."
                textSize = 11f; setTextColor(ThemeTokens.TEXT_MUTED)
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, dp(4), 0, dp(8))
            })
            return
        }
        events.reversed().take(20).forEach { ev -> eventCard(c, ev) }
    }

    private fun startRefresh() {
        val r = object : Runnable {
            override fun run() {
                refreshOnce()
                // no59 ITEM 4: STOPPED — the 3s agent refresh no longer
                // re-arms. InAppLiveDisplay is the single switch (default
                // OFF): with it off the room refreshes exactly once, when
                // startRefresh() posts it. File logging is untouched.
                if (InAppLiveDisplay.liveRefreshAllowed()) {
                    refreshHandler.postDelayed(this, 3_000L)
                }
            }
        }
        refreshRunnable = r
        refreshHandler.post(r)
    }
}

package com.assistant.admin

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.assistant.diagnostic.RuntimeLogger

/**
 * PIN-gated admin panel - the engine setup room.
 *
 * Navigation: PIN -> Adapter -> Categories (plain-language groups of
 * engines) -> Engine -> that engine's settings.
 *
 * Every setting carries:
 *  - a GUIDE readout in plain language (what it is, raise/lower, advantage/
 *    disadvantage, tweak spots incl. risk + gaming cheat spot)
 *  - a live DETECTOR line computed from THIS device's measurements right
 *    now, plus one tap to apply all detector picks for the engine.
 *
 * The HOME screen carries the Worst-Moment Dashboard: when a moment worse
 * than anything before is measured, its adapter shows a NEW entry naming
 * the exact engine to open; that engine's screen offers one-tap apply of
 * the picks archived from that exact moment.
 *
 * Saved values are picked up on each engine's next loop tick - no restart,
 * no rebuild, no code tweaking. Screens build themselves from
 * AdminConfigStore.TUNABLES: exposing a new engine is just adding its
 * Tunables + guides.
 */
class AdminSettingsActivity : Activity() {

    private enum class Screen { PIN, ADAPTERS, ENGINES, SETTINGS }

    private var screen = Screen.PIN
    private var curAdapter = ""
    private var curEngine = ""
    private val editors = LinkedHashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdminConfigStore.initialize(this)
        showPinGate()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.SETTINGS -> showEngines(curAdapter)
            Screen.ENGINES -> showAdapters()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------- shared building blocks ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(24))
    }

    private fun mount(list: LinearLayout) {
        setContentView(ScrollView(this).apply {
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun title(list: LinearLayout, text: String, sub: String? = null) {
        list.addView(TextView(this).apply {
            this.text = text; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        })
        if (sub != null) list.addView(TextView(this).apply {
            this.text = sub; textSize = 13f; setPadding(0, dp(2), 0, dp(8))
        })
    }

    private fun navButton(list: LinearLayout, label: String, onClick: () -> Unit) {
        list.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        })
    }

    // ---------- screen 1: PIN ----------

    private fun showPinGate() {
        screen = Screen.PIN
        val root = page()
        title(root, "Admin PIN")
        val pin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        root.addView(pin)
        navButton(root, "Unlock") {
            if (AdminConfigStore.checkPin(pin.text.toString())) {
                RuntimeLogger.log("admin panel unlocked", "ADMIN")
                showAdapters()
            } else {
                Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
                RuntimeLogger.log("admin PIN rejected", "ADMIN")
            }
        }
        mount(root)
    }

    // ---------- screen 2: adapters + Worst-Moment Dashboard ----------

    private fun showAdapters() {
        screen = Screen.ADAPTERS
        val root = page()
        title(root, "Splendor Admin - Engine Room", "Pick an adapter")
        for (a in AdminConfigStore.ADAPTERS) {
            val engines = AdminConfigStore.enginesFor(a)
            val settings = AdminConfigStore.TUNABLES.count { it.adapter == a }
            val label = if (engines.isEmpty()) a + "  (coming soon)"
                        else a + "  (" + engines.size + " engines - " + settings + " settings)"
            navButton(root, label) { showEngines(a) }
        }

        // ---- Worst-Moment Dashboard ----
        root.addView(TextView(this).apply {
            text = "Worst-Moment Dashboard"
            textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(2))
        })
        root.addView(TextView(this).apply {
            text = "When a moment WORSE than anything before is measured, the setup computed from that exact moment is archived. NEW = not located yet. Open the named engine and tap 'Apply worst-moment archive picks'."
            textSize = 11f; setPadding(0, 0, 0, dp(6))
        })
        for (a in AdminConfigStore.ADAPTERS) {
            val m = AdminWorstMoments.moment(a)
            if (m == null) {
                root.addView(TextView(this).apply {
                    text = "- " + a + ": nothing archived yet (no bad-enough moment measured)"
                    textSize = 12f; setPadding(0, dp(4), 0, 0)
                })
            } else {
                val isNew = m.atMs > m.seenAtMs
                root.addView(TextView(this).apply {
                    text = (if (isNew) "NEW!  " else "") + "- " + a + " (" +
                        AdminWorstMoments.fmtWhen(m.atMs) + "): " + m.summary +
                        "\n   Apply at: " + m.driver
                    textSize = 12f
                    if (isNew) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }

        root.addView(TextView(this).apply {
            text = "Values save live and apply on each engine's next tick. Mirror: Download/SplendorAssist/admin_config.json"
            textSize = 12f; setPadding(0, dp(12), 0, 0)
        })
        // PHASE4: navigate to Agent Room — uses setClassName (cross-module: diagnostic_core→app)
        navButton(root, "🤖  AI Self-Heal Agent Monitor  (Future Rooms)") {
            try {
                val i = android.content.Intent()
                i.setClassName(packageName, "com.assistant.controlroom.ui.FutureRoomsActivity")
                i.putExtra("room_label", "agent")
                startActivity(i)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this, "Open Future Rooms from the main app screen: ${e.message}",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        mount(root)
    }

    // ---------- screen 3: engines of one adapter, grouped by category ----------

    private fun showEngines(adapter: String) {
        screen = Screen.ENGINES
        curAdapter = adapter
        AdminWorstMoments.markSeen(adapter)
        val root = page()
        title(root, adapter, "Pick an engine")
        val cats = AdminConfigStore.categoriesFor(adapter)
        if (cats.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No tunables wired into the admin store for this adapter yet.\n\nWhen this adapter's engine constants are migrated, each engine will appear here automatically with its own settings, guides and detector."
                textSize = 14f; setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            for ((cat, engines) in cats) {
                root.addView(TextView(this).apply {
                    text = cat
                    textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(14), 0, dp(4))
                })
                for (e in engines) {
                    val n = AdminConfigStore.tunablesFor(adapter, e).size
                    navButton(root, e + "  (" + n + " settings)") { showSettings(adapter, e) }
                }
            }
        }
        navButton(root, "< Back to adapters") { showAdapters() }
        mount(root)
    }

    // ---------- screen 4: one engine's settings ----------

    private fun showSettings(adapter: String, engine: String) {
        screen = Screen.SETTINGS
        curAdapter = adapter; curEngine = engine
        editors.clear()
        val root = page()
        title(root, engine, adapter + " - applies on next tick")

        // ---- live detector header (per adapter) ----
        root.addView(TextView(this).apply {
            text = AdminTuningDetector.liveLine(adapter)
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        })
        navButton(root, "Refresh detector reading") { showSettings(adapter, engine) }
        val picks = AdminTuningDetector.picksFor(engine)
        if (picks.isNotEmpty()) {
            navButton(root, "Apply Detector picks for this engine") {
                for (p in picks) editors[p.key]?.setText(fmt(p.value))
                Toast.makeText(this, "Detector values filled in - press Save to apply", Toast.LENGTH_SHORT).show()
            }
        }
        val pickByKey = picks.associateBy { it.key }

        // ---- worst-moment archive for this engine ----
        val worst = AdminWorstMoments.moment(adapter)
        val archived = AdminWorstMoments.archivedPicks(adapter, engine)
        if (worst != null && archived.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "WORST-MOMENT ARCHIVE (" + AdminWorstMoments.fmtWhen(worst.atMs) + "): " + worst.summary
                textSize = 12f; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, 0)
            })
            navButton(root, "Apply worst-moment archive picks") {
                for ((k, v) in archived) editors[k]?.setText(fmt(v))
                Toast.makeText(this, "Worst-moment values filled in - press Save to apply", Toast.LENGTH_SHORT).show()
            }
        }

        for (t in AdminConfigStore.tunablesFor(adapter, engine)) {
            root.addView(TextView(this).apply {
                text = t.label
                textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(14), 0, 0)
            })
            root.addView(TextView(this).apply {
                text = t.key + "   -   default " + fmt(t.def)
                textSize = 11f
            })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(fmt(AdminConfigStore.get(t.key, t.def)))
            }
            editors[t.key] = e
            root.addView(e)

            val pick = pickByKey[t.key]
            if (pick != null) {
                root.addView(TextView(this).apply {
                    text = "DETECTOR says " + fmt(pick.value) + " - " + pick.why
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(2), 0, 0)
                })
            }
            val av = archived[t.key]
            if (av != null) {
                root.addView(TextView(this).apply {
                    text = "WORST-MOMENT pick: " + fmt(av)
                    textSize = 12f
                    setPadding(dp(4), dp(2), 0, 0)
                })
            }

            val guide = AdminTuningGuide.forKey(t.key)
                ?: AdminTuningGuideLag.forKey(t.key)
                ?: AdminTuningGuideStutter.forKey(t.key)
            if (guide != null) {
                val body = TextView(this).apply {
                    text = AdminTuningGuide.render(guide)
                    textSize = 12f
                    visibility = View.GONE
                    setPadding(dp(8), dp(4), 0, dp(4))
                }
                val toggle = Button(this).apply {
                    text = "Guide"
                    isAllCaps = false
                    textSize = 12f
                    setOnClickListener {
                        val open = body.visibility == View.VISIBLE
                        body.visibility = if (open) View.GONE else View.VISIBLE
                        text = if (open) "Guide" else "Hide guide"
                    }
                }
                root.addView(toggle)
                root.addView(body)
            }
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        row.addView(Button(this).apply {
            text = "Save"; isAllCaps = false
            setOnClickListener { saveEngine(adapter, engine) }
        })
        row.addView(Button(this).apply {
            text = "Reset engine"; isAllCaps = false
            setOnClickListener {
                AdminConfigStore.resetEngine(adapter, engine)
                showSettings(adapter, engine)
                Toast.makeText(this@AdminSettingsActivity, engine + " defaults restored", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(row)
        navButton(root, "< Back to " + adapter) { showEngines(adapter) }
        mount(root)
    }

    private fun saveEngine(adapter: String, engine: String) {
        var saved = 0; var bad = 0
        for (t in AdminConfigStore.tunablesFor(adapter, engine)) {
            val raw = editors[t.key]?.text?.toString()?.trim() ?: continue
            val v = raw.toFloatOrNull()
            if (v == null) { bad++; continue }
            if (v != AdminConfigStore.get(t.key, t.def)) {
                AdminConfigStore.set(t.key, v); saved++
            }
        }
        RuntimeLogger.log("admin save [" + engine + "]: " + saved + " changed, " + bad + " invalid", "ADMIN")
        Toast.makeText(this,
            saved.toString() + " saved" + (if (bad > 0) ", " + bad + " invalid" else ""),
            Toast.LENGTH_SHORT).show()
    }

    private fun fmt(v: Float): String =
        if (v == Math.floor(v.toDouble()).toFloat()) v.toInt().toString() else v.toString()
}

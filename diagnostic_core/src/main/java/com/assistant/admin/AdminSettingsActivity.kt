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
 * PIN-gated admin panel — the engine setup room.
 *
 * Navigation: PIN -> Adapter -> Engine -> that engine's tunables.
 * Every tunable carries a GUIDE readout (anatomy, code reaction on
 * raise/lower, advantage/disadvantage, tweak spots) from AdminTuningGuide.
 * Saved values are picked up on each engine's next loop tick — no
 * restart, no rebuild, no code tweaking.
 *
 * The screens build themselves from AdminConfigStore.TUNABLES: exposing a
 * new engine (even 50 of them) is just adding its Tunables + guides.
 * Adapters listed in AdminConfigStore.ADAPTERS always get a button, even
 * before their constants are migrated.
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

    override fun onBackPressed() {
        when (screen) {
            Screen.SETTINGS -> showEngines(curAdapter)
            Screen.ENGINES -> showAdapters()
            else -> super.onBackPressed()
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

    // ---------- screen 2: adapters ----------

    private fun showAdapters() {
        screen = Screen.ADAPTERS
        val root = page()
        title(root, "Splendor Admin — Engine Room", "Pick an adapter")
        for (a in AdminConfigStore.ADAPTERS) {
            val engines = AdminConfigStore.enginesFor(a)
            val settings = AdminConfigStore.TUNABLES.count { it.adapter == a }
            val label = if (engines.isEmpty()) a + "  (coming soon)"
                        else a + "  (" + engines.size + " engines · " + settings + " settings)"
            navButton(root, label) { showEngines(a) }
        }
        root.addView(TextView(this).apply {
            text = "Values save live and apply on each engine's next tick. Mirror: Download/SplendorAssist/admin_config.json"
            textSize = 12f; setPadding(0, dp(12), 0, 0)
        })
        mount(root)
    }

    // ---------- screen 3: engines of one adapter ----------

    private fun showEngines(adapter: String) {
        screen = Screen.ENGINES
        curAdapter = adapter
        val root = page()
        title(root, adapter, "Pick an engine")
        val engines = AdminConfigStore.enginesFor(adapter)
        if (engines.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No tunables wired into the admin store for this adapter yet.\n\nWhen this adapter's engine constants are migrated, each engine will appear here automatically with its own settings and guides."
                textSize = 14f; setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            for (e in engines) {
                val n = AdminConfigStore.tunablesFor(adapter, e).size
                navButton(root, e + "  (" + n + " settings)") { showSettings(adapter, e) }
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
        title(root, engine, adapter + " · applies on next tick")

        for (t in AdminConfigStore.tunablesFor(adapter, engine)) {
            root.addView(TextView(this).apply {
                text = t.label
                textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(14), 0, 0)
            })
            root.addView(TextView(this).apply {
                text = t.key + "   ·   default " + fmt(t.def)
                textSize = 11f
            })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(fmt(AdminConfigStore.get(t.key, t.def)))
            }
            editors[t.key] = e
            root.addView(e)

            val guide = AdminTuningGuide.forKey(t.key)
            if (guide != null) {
                val body = TextView(this).apply {
                    text = AdminTuningGuide.render(guide)
                    textSize = 12f
                    visibility = View.GONE
                    setPadding(dp(8), dp(4), 0, dp(4))
                }
                val toggle = Button(this).apply {
                    text = "Guide ▾"
                    isAllCaps = false
                    textSize = 12f
                    setOnClickListener {
                        val open = body.visibility == View.VISIBLE
                        body.visibility = if (open) View.GONE else View.VISIBLE
                        text = if (open) "Guide ▾" else "Guide ▴"
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

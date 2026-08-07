package com.assistant.admin

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.assistant.diagnostic.RuntimeLogger

/**
 * PIN-gated admin panel for the 23 runtime tunables.
 *
 * UI is built programmatically (no layout XML) so diagnostic_core stays
 * resource-light. Saved values are picked up on each engine's next loop
 * tick - no restart required. Default PIN is 2468; change it with
 * AdminConfigStore.setPin().
 */
class AdminSettingsActivity : Activity() {

    private val editors = LinkedHashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdminConfigStore.initialize(this)
        showPinGate()
    }

    private fun showPinGate() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Admin PIN"; textSize = 20f })
        val pin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        root.addView(pin)
        root.addView(Button(this).apply {
            text = "Unlock"
            setOnClickListener {
                if (AdminConfigStore.checkPin(pin.text.toString())) {
                    RuntimeLogger.log("admin panel unlocked", "ADMIN")
                    showPanel()
                } else {
                    Toast.makeText(this@AdminSettingsActivity, "Wrong PIN", Toast.LENGTH_SHORT).show()
                    RuntimeLogger.log("admin PIN rejected", "ADMIN")
                }
            }
        })
        setContentView(root)
    }

    private fun showPanel() {
        editors.clear()
        val pad = (12 * resources.displayMetrics.density).toInt()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        list.addView(TextView(this).apply { text = "Admin Settings"; textSize = 20f })
        for (t in AdminConfigStore.TUNABLES) {
            list.addView(TextView(this).apply { text = t.label; setPadding(0, pad, 0, 0) })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(fmt(AdminConfigStore.get(t.key, t.def)))
            }
            editors[t.key] = e
            list.addView(e)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Save all"
            setOnClickListener { saveAll() }
        })
        row.addView(Button(this).apply {
            text = "Reset defaults"
            setOnClickListener {
                AdminConfigStore.resetAll()
                showPanel()
                Toast.makeText(this@AdminSettingsActivity, "Defaults restored", Toast.LENGTH_SHORT).show()
            }
        })
        list.addView(row)
        setContentView(ScrollView(this).apply {
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun saveAll() {
        var saved = 0; var bad = 0
        for (t in AdminConfigStore.TUNABLES) {
            val raw = editors[t.key]?.text?.toString()?.trim() ?: continue
            val v = raw.toFloatOrNull()
            if (v == null) { bad++; continue }
            if (v != AdminConfigStore.get(t.key, t.def)) {
                AdminConfigStore.set(t.key, v); saved++
            }
        }
        RuntimeLogger.log("admin save: " + saved + " changed, " + bad + " invalid", "ADMIN")
        Toast.makeText(this,
            saved.toString() + " saved" + (if (bad > 0) ", " + bad + " invalid" else ""),
            Toast.LENGTH_SHORT).show()
    }

    private fun fmt(v: Float): String =
        if (v == Math.floor(v.toDouble()).toFloat()) v.toInt().toString() else v.toString()
}

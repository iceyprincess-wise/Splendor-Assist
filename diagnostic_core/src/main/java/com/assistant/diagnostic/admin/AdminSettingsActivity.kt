package com.assistant.diagnostic.admin

// TASK A - ADMIN SETTINGS (new isolated file per directive)
import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * PIN-gated admin settings screen. Programmatic UI (no layout resources
 * touched). Gate first; on success the 23 tunables render with current
 * values. Save writes prefs + JSON mirror through AdminConfigStore.
 */
class AdminSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdminConfigStore.initialize(applicationContext)
        showPinGate()
    }

    private fun showPinGate() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply { text = "Admin PIN"; textSize = 20f }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter PIN"
        }
        val unlock = Button(this).apply { text = "Unlock" }
        val msg = TextView(this)
        unlock.setOnClickListener {
            if (AdminConfigStore.checkPin(input.text.toString().trim())) showSettings()
            else msg.text = "Wrong PIN"
        }
        root.addView(title); root.addView(input); root.addView(unlock); root.addView(msg)
        setContentView(root)
    }

    private fun showSettings() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val fields = HashMap<String, EditText>()
        for (s in AdminConfigStore.SPECS) {
            list.addView(TextView(this).apply { text = s.label; textSize = 13f })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(AdminConfigStore.get(s.key).toString())
            }
            fields[s.key] = e
            list.addView(e)
        }
        val save = Button(this).apply { text = "Save all" }
        val reset = Button(this).apply { text = "Reset to defaults" }
        val status = TextView(this)
        save.setOnClickListener {
            var ok = 0
            for (s in AdminConfigStore.SPECS) {
                val v = fields[s.key]?.text?.toString()?.toFloatOrNull()
                if (v != null) { AdminConfigStore.set(applicationContext, s.key, v); ok++ }
            }
            status.text = "Saved " + ok + "/" + AdminConfigStore.SPECS.size
        }
        reset.setOnClickListener {
            AdminConfigStore.resetAll(applicationContext)
            showSettings()
        }
        list.addView(save); list.addView(reset); list.addView(status)
        val scroll = ScrollView(this)
        scroll.addView(list)
        setContentView(scroll)
    }
}

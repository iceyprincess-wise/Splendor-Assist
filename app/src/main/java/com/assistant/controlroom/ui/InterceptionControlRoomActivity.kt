package com.assistant.controlroom.ui

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.assistant.overlay.R
import com.assistant.overlay.repository.InterceptionRepository
import com.assistant.FrameAssembler
import com.assistant.GestureExecutionAuthority
import com.assistant.RuntimeCoordinator
import com.assistant.RuntimeDecisionLoop
import com.assistant.RuntimeHealthMonitor
import com.assistant.RuntimePerformanceCoordinator
import com.assistant.SmartAssistRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * Interception control room.
 *
 * Settings half: the InterceptionRepository sliders/switches (unchanged).
 * Live half: reads the SAME runtime surfaces the SmartAssist control room
 * reads and answers the interception questions truthfully:
 *  - is the InterceptMatrix contributor registered and is its gate
 *    (frame.trusted && !frame.hasBall) open right now?
 *  - what does the field read look like (players, opponents, lanes, zones)
 *    - the inputs the intercept-vector math runs on
 *  - are decisions flowing and gestures being accepted?
 *
 * Inner-rooms UI: the live half is the dashboard bulb grid now
 * (PARAMETERS on top, LIVE COUNTERS in the middle), not the raw text
 * dump — same sources, same values, counting live.
 */
class InterceptionControlRoomActivity : AppCompatActivity() {

    private lateinit var repository: InterceptionRepository
    private var liveGrid: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_interception_control_room)

        repository = InterceptionRepository.shared(this)

        val switchEnabled = findViewById<MaterialSwitch>(R.id.switch_enabled)
        val switchAutoIntercept = findViewById<MaterialSwitch>(R.id.switch_auto_intercept)
        val seekAwareness = findViewById<Slider>(R.id.seekbar_awareness)
        val seekPrediction = findViewById<Slider>(R.id.seekbar_prediction)
        val textAwareness = findViewById<TextView>(R.id.text_awareness_value)
        val textPrediction = findViewById<TextView>(R.id.text_prediction_value)
        val textStatus = findViewById<TextView>(R.id.text_status)
        val buttonSave = findViewById<Button>(R.id.button_save)

        installLivePanel(textStatus)

        lifecycleScope.launch {
            repository.state.collectLatest { state ->
                switchEnabled.isChecked = state.enabled
                switchAutoIntercept.isChecked = state.autoIntercept
                seekAwareness.value = state.awareness.toFloat()
                seekPrediction.value = state.prediction.toFloat()
                textAwareness.text = "${state.awareness}%"
                textPrediction.text = "${state.prediction}%"
                textStatus.text = if (state.enabled) "ACTIVE" else "INACTIVE"
            }
        }

        seekAwareness.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            textAwareness.text = "$progress%"
            if (fromUser) {
                repository.updateAwareness(progress)
            }
        }

        seekPrediction.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            textPrediction.text = "$progress%"
            if (fromUser) {
                repository.updatePrediction(progress)
            }
        }

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            repository.updateEnabled(isChecked)
        }

        switchAutoIntercept.setOnCheckedChangeListener { _, isChecked ->
            repository.updateAutoIntercept(isChecked)
        }

        buttonSave.setOnClickListener {
            Toast.makeText(this, "Interception settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /* Live runtime panel injected under the status line - no layout edit needed. */
    // Inner-rooms UI: the panel is the dashboard bulb grid now
    // (ControlRoomBulbs), not the raw text dump.

    private fun installLivePanel(anchor: TextView) {
        val parent = anchor.parent as? ViewGroup ?: return
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val refresh = Button(this).apply {
            text = "Refresh live interception status"
            isAllCaps = false
            setBackgroundColor(ThemeTokens.BUTTON)
            setTextColor(Color.WHITE)
            textSize = 14f
            setOnClickListener { renderLivePanel() }
        }
        val index = parent.indexOfChild(anchor) + 1
        parent.addView(grid, index)
        parent.addView(refresh, index + 1)
        liveGrid = grid
        renderLivePanel()
    }

    private fun renderLivePanel() {
        val grid = liveGrid ?: return
        try {
            val runtime = RuntimeCoordinator.runtimeState()
            val health = RuntimeHealthMonitor.runtimeHealthSnapshot()
            val frame = FrameAssembler.frameRuntimeSnapshot()
            val decision = RuntimeDecisionLoop.decisionRuntimeSnapshot()
            val execution = GestureExecutionAuthority.executionRuntimeSnapshot()
            val registry =
                com.assistant.runtime.GameplayEngineRegistry.registryRuntimeSnapshot()

            val trusted = frame["trusted"] as? Boolean ?: false
            val hasBall = frame["hasBall"] as? Boolean ?: false
            val gateOpen = trusted && !hasBall
            val gate = when {
                !trusted -> "CLOSED - no trusted game frame yet (open the game; interception is silent on menus)"
                hasBall -> "CLOSED - you have the ball (interception hunts while the OPPONENT has it)"
                else -> "OPEN - live frames, hunting intercept vectors"
            }
            val registered =
                (registry["names"]?.toString() ?: "").contains("InterceptMatrix")

            // No66-D33: a cold frame carries only "frames"/"state" — the five
            // field keys below are absent and used to render as literal "null".
            val cold = !frame.containsKey("trusted")

            grid.removeAllViews()
            with(ControlRoomBulbs) {
                grid.addView(sectionHeader(this@InterceptionControlRoomActivity, getString(R.string.control_room_live_counters)))
                renderFullWidthCell(grid, BulbEntry(
                    "INTERCEPT GATE",
                    if (gateOpen) BulbState.GREEN else BulbState.YELLOW, gate
                ))
                renderBulbSection(grid, listOf(
                    BulbEntry("InterceptMatrix", if (registered) BulbState.GREEN else BulbState.YELLOW, if (registered) "Registered" else "Not registered")
                ))
                if (cold) {
                    grid.addView(monoBlock(this@InterceptionControlRoomActivity, "Field read: no frame yet (runtime cold — open the game)"))
                }
                grid.addView(sectionHeader(this@InterceptionControlRoomActivity, "FIELD"))
                renderBulbSection(grid, listOf(
                    BulbEntry("Players", if (text(frame, "players") != "—") BulbState.GREEN else BulbState.YELLOW, text(frame, "players")),
                    BulbEntry("Opponents", if (text(frame, "opponents") != "—") BulbState.GREEN else BulbState.YELLOW, text(frame, "opponents")),
                    BulbEntry("Lanes", if (text(frame, "viableLanes") != "—") BulbState.GREEN else BulbState.YELLOW, text(frame, "viableLanes")),
                    BulbEntry("Zones", if (text(frame, "zones") != "—") BulbState.GREEN else BulbState.YELLOW, text(frame, "zones")),
                    BulbEntry("Confidence", if (trusted) BulbState.GREEN else BulbState.YELLOW, text(frame, "confidence")),
                    boolBulb(frame, "trusted", "Trusted", "Yes", "No"),
                    BulbEntry("Has ball", if (hasBall) BulbState.YELLOW else BulbState.GREEN, if (hasBall) "Yes" else "No")
                ))
                grid.addView(sectionHeader(this@InterceptionControlRoomActivity, "DECISION"))
                renderBulbSection(grid, listOf(
                    countBulb(decision, "decisions", "Decisions"),
                    countBulb(decision, "routed", "Routed"),
                    BulbEntry("Last action", if (count(decision, "routed") > 0) BulbState.GREEN else BulbState.YELLOW, text(decision, "lastAction")),
                    BulbEntry("Weight", if (count(decision, "routed") > 0) BulbState.GREEN else BulbState.YELLOW, text(decision, "lastWeight"))
                ))
                grid.addView(sectionHeader(this@InterceptionControlRoomActivity, "EXECUTION"))
                renderBulbSection(grid, listOf(
                    countBulb(execution, "requested", "Requested"),
                    countBulb(execution, "accepted", "Accepted"),
                    failBulb(execution, "failed", "Failed")
                ))
                grid.addView(sectionHeader(this@InterceptionControlRoomActivity, "RUNTIME"))
                renderBulbSection(grid, listOf(
                    boolBulb(runtime, "runtimeReady", "Runtime ready"),
                    countBulb(runtime, "busPending", "Bus pending"),
                    issuesBulb(health)
                ))
            }
        } catch (t: Throwable) {
            grid.removeAllViews()
            grid.addView(ControlRoomBulbs.monoBlock(this, "Live interception status unavailable: " + (t.message ?: "runtime not started yet")))
        }
    }

    override fun onResume() {
        super.onResume()
        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )
        renderLivePanel()
    }
}

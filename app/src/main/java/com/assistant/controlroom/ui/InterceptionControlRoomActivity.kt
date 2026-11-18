package com.assistant.controlroom.ui

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
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
 */
class InterceptionControlRoomActivity : AppCompatActivity() {

    private lateinit var repository: InterceptionRepository
    private var liveText: TextView? = null

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
    // No66-D32 (Q61-1): the panel used to be a default-styled Button/TextView
    // with raw-pixel padding. It now uses ThemeTokens like the rest of the
    // room, with dp padding (same helper shape as the other rooms).
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun installLivePanel(anchor: TextView) {
        val parent = anchor.parent as? ViewGroup ?: return
        val refresh = Button(this).apply {
            text = "Refresh live interception status"
            isAllCaps = false
            setBackgroundColor(ThemeTokens.BUTTON)
            setTextColor(Color.WHITE)
            textSize = 14f
            setOnClickListener { liveText?.text = liveInterceptionStatus() }
        }
        val live = TextView(this).apply {
            textSize = 12f
            setTextColor(ThemeTokens.TEXT_MUTED)
            setPadding(0, dp(16), 0, dp(16))
        }
        val index = parent.indexOfChild(anchor) + 1
        parent.addView(refresh, index)
        parent.addView(live, index + 1)
        liveText = live
        live.text = liveInterceptionStatus()
    }

    private fun liveInterceptionStatus(): String = try {
        val runtime = RuntimeCoordinator.runtimeState()
        val health = RuntimeHealthMonitor.runtimeHealthSnapshot()
        val frame = FrameAssembler.frameRuntimeSnapshot()
        val decision = RuntimeDecisionLoop.decisionRuntimeSnapshot()
        val execution = GestureExecutionAuthority.executionRuntimeSnapshot()
        val registry =
            com.assistant.runtime.GameplayEngineRegistry.registryRuntimeSnapshot()

        val trusted = frame["trusted"] as? Boolean ?: false
        val hasBall = frame["hasBall"] as? Boolean ?: false
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

        buildString {
            append("INTERCEPT GATE: ").append(gate).append('\n')
            append("InterceptMatrix contributor registered = ").append(registered).append('\n')
            if (cold) append("Field read: no frame yet (runtime cold — open the game)\n")
            append("Field read: players=").append(frame["players"] ?: "—")
                .append(" opponents=").append(frame["opponents"] ?: "—")
                .append(" lanes=").append(frame["viableLanes"] ?: "—")
                .append(" zones=").append(frame["zones"] ?: "—").append('\n')
            append("Frame confidence=").append(frame["confidence"] ?: "—")
                .append(" trusted=").append(trusted)
                .append(" hasBall=").append(hasBall).append('\n')
            append("Decisions=").append(decision["decisions"])
                .append(" routed=").append(decision["routed"])
                .append(" lastAction=").append(decision["lastAction"])
                .append(" weight=").append(decision["lastWeight"]).append('\n')
            append("Execution: requested=").append(execution["requested"])
                .append(" accepted=").append(execution["accepted"])
                .append(" failed=").append(execution["failed"]).append('\n')
            append("Runtime ready=").append(runtime["runtimeReady"])
                .append(" busPending=").append(runtime["busPending"]).append('\n')
            append("Health: ").append(health["degradedReasons"])
        }
    } catch (t: Throwable) {
        "Live interception status unavailable: " + (t.message ?: "runtime not started yet")
    }

    override fun onResume() {
        super.onResume()
        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )
        liveText?.text = liveInterceptionStatus()
    }
}

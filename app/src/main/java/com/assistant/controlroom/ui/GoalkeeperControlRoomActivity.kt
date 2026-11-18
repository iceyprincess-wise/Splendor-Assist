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
import com.assistant.overlay.repository.GoalkeeperRepository
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
 * Goalkeeper control room.
 *
 * Settings half: the GoalkeeperRepository sliders/switches (unchanged).
 * Live half: reads the SAME runtime surfaces the SmartAssist control room
 * reads (RuntimeCoordinator / FrameAssembler / RuntimeDecisionLoop /
 * ContributionRegistry / GestureExecutionAuthority / GameplayEngineRegistry)
 * and answers the keeper questions truthfully:
 *  - is the KeeperFeedback contributor registered and is its gate
 *    (frame.trusted && !frame.hasBall) open right now?
 *  - are decisions flowing, is the emergency lane (keeper outranks normal
 *    contributions) draining, are gestures being accepted?
 */
class GoalkeeperControlRoomActivity : AppCompatActivity() {

    private lateinit var repository: GoalkeeperRepository
    private var liveText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_goalkeeper_control_room)

        // No63-D16: shared() — a direct construction here used to create
        // a third GoalkeeperRepository instance with its own flow; a save
        // from one instance could be reverted by the next save from
        // another instance's stale flow (lost update).
        repository = GoalkeeperRepository.shared(this)

        val switchEnabled = findViewById<MaterialSwitch>(R.id.switch_enabled)
        val switchAggressive = findViewById<MaterialSwitch>(R.id.switch_aggressive)
        val seekPositioning = findViewById<Slider>(R.id.seekbar_positioning)
        val seekReactions = findViewById<Slider>(R.id.seekbar_reactions)
        val textPositioning = findViewById<TextView>(R.id.text_positioning_value)
        val textReactions = findViewById<TextView>(R.id.text_reactions_value)
        val textStatus = findViewById<TextView>(R.id.text_status)
        val buttonSave = findViewById<Button>(R.id.button_save)

        installLivePanel(textStatus)

        lifecycleScope.launch {
            repository.state.collectLatest { state ->
                switchEnabled.isChecked = state.enabled
                switchAggressive.isChecked = state.aggressiveMode
                seekPositioning.value = state.positioning.toFloat()
                seekReactions.value = state.reactions.toFloat()
                textPositioning.text = "${state.positioning}%"
                textReactions.text = "${state.reactions}%"
                textStatus.text = if (state.enabled) "ACTIVE" else "INACTIVE"
            }
        }

        seekPositioning.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            textPositioning.text = "$progress%"
            if (fromUser) {
                repository.updatePositioning(progress)
            }
        }

        seekReactions.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            textReactions.text = "$progress%"
            if (fromUser) {
                repository.updateReactions(progress)
            }
        }

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            repository.updateEnabled(isChecked)
        }

        switchAggressive.setOnCheckedChangeListener { _, isChecked ->
            repository.updateAggressiveMode(isChecked)
        }

        buttonSave.setOnClickListener {
            Toast.makeText(this, "Goalkeeper settings saved", Toast.LENGTH_SHORT).show()
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
            text = "Refresh live keeper status"
            isAllCaps = false
            setBackgroundColor(ThemeTokens.BUTTON)
            setTextColor(Color.WHITE)
            textSize = 14f
            setOnClickListener { liveText?.text = liveKeeperStatus() }
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
        live.text = liveKeeperStatus()
    }

    private fun liveKeeperStatus(): String = try {
        val runtime = RuntimeCoordinator.runtimeState()
        val health = RuntimeHealthMonitor.runtimeHealthSnapshot()
        val frame = FrameAssembler.frameRuntimeSnapshot()
        val decision = RuntimeDecisionLoop.decisionRuntimeSnapshot()
        val contributions =
            com.assistant.execution.ContributionRegistry.contributionRuntimeSnapshot()
        val execution = GestureExecutionAuthority.executionRuntimeSnapshot()
        val registry =
            com.assistant.runtime.GameplayEngineRegistry.registryRuntimeSnapshot()

        val trusted = frame["trusted"] as? Boolean ?: false
        val hasBall = frame["hasBall"] as? Boolean ?: false
        val gate = when {
            !trusted -> "CLOSED - no trusted game frame yet (open the game; the keeper stays silent on menus)"
            hasBall -> "CLOSED - you have the ball (the keeper contributes only while defending)"
            else -> "OPEN - trusted frame + opponent ball: keeper logic is live"
        }
        val registered =
            (registry["names"]?.toString() ?: "").contains("KeeperFeedback")

        // No66-D33: a cold frame carries only "frames"/"state" — confidence
        // is absent and used to render as literal "null".
        val cold = !frame.containsKey("trusted")

        buildString {
            append("KEEPER GATE: ").append(gate).append('\n')
            append("KeeperFeedback contributor registered = ").append(registered).append('\n')
            if (cold) append("Frame: no frame yet (runtime cold — open the game)\n")
            append("Frame: trusted=").append(trusted)
                .append(" hasBall=").append(hasBall)
                .append(" confidence=").append(frame["confidence"] ?: "—").append('\n')
            append("Decisions=").append(decision["decisions"])
                .append(" routed=").append(decision["routed"])
                .append(" lastAction=").append(decision["lastAction"]).append('\n')
            // D7: the emergency lane is the emergencyOffered/Accepted/
            // Rejected counters, not the parked-map counters - drained can
            // never include GK/INT (they bypass the map) and pending is the
            // map size. Show both lanes under their own labels.
            append("Emergency lane (keeper outranks): offered=").append(contributions["emergencyOffered"])
                .append(" accepted=").append(contributions["emergencyAccepted"])
                .append(" rejected=").append(contributions["emergencyRejected"]).append('\n')
            append("Parked (map): offered=").append(contributions["offered"])
                .append(" drained=").append(contributions["drained"])
                .append(" pending=").append(contributions["pending"]).append('\n')
            append("Execution: requested=").append(execution["requested"])
                .append(" accepted=").append(execution["accepted"])
                .append(" failed=").append(execution["failed"]).append('\n')
            append("Runtime ready=").append(runtime["runtimeReady"])
                .append(" busPending=").append(runtime["busPending"]).append('\n')
            append("Health: ").append(health["degradedReasons"])
        }
    } catch (t: Throwable) {
        "Live keeper status unavailable: " + (t.message ?: "runtime not started yet")
    }

    override fun onResume() {
        super.onResume()
        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )
        liveText?.text = liveKeeperStatus()
    }
}

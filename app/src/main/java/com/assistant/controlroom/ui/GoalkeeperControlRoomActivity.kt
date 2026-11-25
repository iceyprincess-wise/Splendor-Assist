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
 *
 * Inner-rooms UI: the live half is the dashboard bulb grid now
 * (PARAMETERS on top, LIVE COUNTERS in the middle), not the raw text
 * dump — same sources, same values, counting live.
 */
class GoalkeeperControlRoomActivity : AppCompatActivity() {

    private lateinit var repository: GoalkeeperRepository
    private var liveGrid: LinearLayout? = null

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
    // Inner-rooms UI: the panel is the dashboard bulb grid now
    // (ControlRoomBulbs), not the raw text dump.

    private fun installLivePanel(anchor: TextView) {
        val parent = anchor.parent as? ViewGroup ?: return
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val refresh = Button(this).apply {
            text = "Refresh live keeper status"
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
            val contributions =
                com.assistant.execution.ContributionRegistry.contributionRuntimeSnapshot()
            val execution = GestureExecutionAuthority.executionRuntimeSnapshot()
            val registry =
                com.assistant.runtime.GameplayEngineRegistry.registryRuntimeSnapshot()

            val trusted = frame["trusted"] as? Boolean ?: false
            val hasBall = frame["hasBall"] as? Boolean ?: false
            val gateOpen = trusted && !hasBall
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

            grid.removeAllViews()
            with(ControlRoomBulbs) {
                grid.addView(sectionHeader(this@GoalkeeperControlRoomActivity, getString(R.string.control_room_live_counters)))
                renderFullWidthCell(grid, BulbEntry(
                    "KEEPER GATE",
                    if (gateOpen) BulbState.GREEN else BulbState.YELLOW, gate
                ))
                renderBulbSection(grid, listOf(
                    BulbEntry("KeeperFeedback", if (registered) BulbState.GREEN else BulbState.YELLOW, if (registered) "Registered" else "Not registered")
                ))
                if (cold) {
                    grid.addView(monoBlock(this@GoalkeeperControlRoomActivity, "Frame: no frame yet (runtime cold — open the game)"))
                }
                grid.addView(sectionHeader(this@GoalkeeperControlRoomActivity, "FRAME"))
                renderBulbSection(grid, listOf(
                    boolBulb(frame, "trusted", "Trusted", "Yes", "No"),
                    BulbEntry("Has ball", if (hasBall) BulbState.YELLOW else BulbState.GREEN, if (hasBall) "Yes" else "No"),
                    BulbEntry("Confidence", if (trusted) BulbState.GREEN else BulbState.YELLOW, text(frame, "confidence"))
                ))
                grid.addView(sectionHeader(this@GoalkeeperControlRoomActivity, "DECISION"))
                renderBulbSection(grid, listOf(
                    countBulb(decision, "decisions", "Decisions"),
                    countBulb(decision, "routed", "Routed"),
                    BulbEntry("Last action", if (count(decision, "routed") > 0) BulbState.GREEN else BulbState.YELLOW, text(decision, "lastAction"))
                ))
                // D7: the emergency lane is the emergencyOffered/Accepted/
                // Rejected counters, not the parked-map counters - drained can
                // never include GK/INT (they bypass the map) and pending is the
                // map size. Show both lanes under their own labels.
                grid.addView(sectionHeader(this@GoalkeeperControlRoomActivity, "EMERGENCY LANE"))
                renderBulbSection(grid, listOf(
                    countBulb(contributions, "emergencyOffered", "Offered"),
                    countBulb(contributions, "emergencyAccepted", "Accepted"),
                    failBulb(contributions, "emergencyRejected", "Rejected")
                ))
                grid.addView(sectionHeader(this@GoalkeeperControlRoomActivity, "PARKED"))
                renderBulbSection(grid, listOf(
                    countBulb(contributions, "offered", "Offered"),
                    countBulb(contributions, "drained", "Drained"),
                    countBulb(contributions, "pending", "Pending")
                ))
                grid.addView(sectionHeader(this@GoalkeeperControlRoomActivity, "EXECUTION"))
                renderBulbSection(grid, listOf(
                    countBulb(execution, "requested", "Requested"),
                    countBulb(execution, "accepted", "Accepted"),
                    failBulb(execution, "failed", "Failed")
                ))
                grid.addView(sectionHeader(this@GoalkeeperControlRoomActivity, "RUNTIME"))
                renderBulbSection(grid, listOf(
                    boolBulb(runtime, "runtimeReady", "Runtime ready"),
                    countBulb(runtime, "busPending", "Bus pending"),
                    issuesBulb(health)
                ))
            }
        } catch (t: Throwable) {
            grid.removeAllViews()
            grid.addView(ControlRoomBulbs.monoBlock(this, "Live keeper status unavailable: " + (t.message ?: "runtime not started yet")))
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

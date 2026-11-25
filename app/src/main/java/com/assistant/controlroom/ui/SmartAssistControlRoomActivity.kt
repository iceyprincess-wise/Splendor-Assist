package com.assistant.controlroom.ui

import android.os.Bundle
import android.widget.Button
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.assistant.overlay.R
import com.assistant.SmartAssistRepository
import com.assistant.overlay.bridge.SmartAssistControlRoomBridge
import com.assistant.RuntimePerformanceCoordinator
import com.assistant.RuntimeVisualizationRegistry

class SmartAssistControlRoomActivity : AppCompatActivity() {

override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
setContentView(R.layout.activity_smartassist_control_room)

        val repo = SmartAssistRepository(this)

        // No66-D16: the bridge was dead (zero callers repo-wide) — the room
        // now owns its write path through it (save/thresholds/authority).
        val bridge = SmartAssistControlRoomBridge(repo)

        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )

        val config = SmartAssistRepository.configuration()

        val enabled = findViewById<MaterialSwitch>(R.id.swEnabled)
        val panic = findViewById<MaterialSwitch>(R.id.swPanic)
        val pass = findViewById<Slider>(R.id.passSeek)
        val shot = findViewById<Slider>(R.id.shotSeek)
        val cross = findViewById<Slider>(R.id.crossSeek)

        enabled.isChecked = SmartAssistRepository.enabled()
        panic.isChecked = SmartAssistRepository.panicActive()
        pass.value = config.passThreshold.toFloat()
        shot.value = config.shotThreshold.toFloat()
        cross.value = config.crossThreshold.toFloat()

        // Inner-rooms UI: the raw tvRuntime/tvMetrics dump is replaced by
        // the dashboard bulb grid (PARAMETERS on top, LIVE COUNTERS in
        // the middle). The counters refresh here and on save, not on
        // every slider tick (No66-D26/D27 behaviour kept).
        refreshLiveCounters()

        val authoritySlider =
            findViewById<com.google.android.material.slider.Slider>(R.id.authoritySeek)

        val authorityLabel =
            findViewById<TextView>(R.id.tvAuthorityValue)

        authoritySlider.value=config.authority.toFloat()

        authorityLabel.text =
            "${config.authority}% (${RuntimePerformanceCoordinator.runtimeAuthority()} Runtime)"

        authoritySlider.addOnChangeListener { _, value, _ ->

            // No66-D34: the bridge owns the pair-write (repo + coordinator).
            bridge.updateAuthority(value.toInt())

            authorityLabel.text =
                "${value.toInt()}% (${RuntimePerformanceCoordinator.runtimeAuthority()} Runtime)"

            refreshEngineStatus()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {

            // No66-D16/D26: the write path goes through the bridge (it was
            // dead — zero callers). One honest toast: saveCurrentSettings()
            // reports false instead of the old double "saved" toasts.
            bridge.updateEnabled(enabled.isChecked)
            bridge.updatePanicMode(panic.isChecked)
            bridge.updateThresholds(
                pass.value.toInt(),
                shot.value.toInt(),
                cross.value.toInt()
            )

            val saved = bridge.saveCurrentSettings()

            Toast.makeText(
                this,
                if (saved) "Smart Assist settings saved"
                else "Smart Assist save failed — settings not stored",
                Toast.LENGTH_SHORT
            ).show()

            refreshLiveCounters()

            finish()
        }

        enabled.setOnCheckedChangeListener { _, _ -> refreshLiveCounters() }
        panic.setOnCheckedChangeListener { _, _ -> refreshLiveCounters() }
    }

    override fun onResume() {
        super.onResume()

        // No66-D27: restore ALL controls, not just the switches. ModMenu
        // can change thresholds/authority while this room is paused, and
        // the sliders used to keep showing stale values on return.
        val config = SmartAssistRepository.configuration()

        findViewById<MaterialSwitch>(R.id.swEnabled).isChecked =
            SmartAssistRepository.enabled()

        findViewById<MaterialSwitch>(R.id.swPanic).isChecked =
            SmartAssistRepository.panicActive()

        findViewById<Slider>(R.id.passSeek).value =
            config.passThreshold.toFloat()
        findViewById<Slider>(R.id.shotSeek).value =
            config.shotThreshold.toFloat()
        findViewById<Slider>(R.id.crossSeek).value =
            config.crossThreshold.toFloat()
        findViewById<Slider>(R.id.authoritySeek).value =
            config.authority.toFloat()
        findViewById<TextView>(R.id.tvAuthorityValue).text =
            "${config.authority}% (${RuntimePerformanceCoordinator.runtimeAuthority()} Runtime)"

        refreshLiveCounters()
    }

    // Inner-rooms UI: replaces the old refreshRuntime()/refreshMetrics()
    // raw text dump with the dashboard bulb grid. Same live sources, same
    // values — RUNTIME, HEALTH, FRAME, DECISION, CONTRIBUTIONS,
    // EXECUTION, REGISTRY — plus the registry names/capabilities as a
    // bounded mono block (fixed content, not a growing log). The two
    // switch bulbs read the live switch positions, as tvRuntime did.
    private fun refreshLiveCounters() {
        val grid = findViewById<LinearLayout>(R.id.liveCounterGrid) ?: return
        val enabled = findViewById<MaterialSwitch>(R.id.swEnabled).isChecked
        val panic = findViewById<MaterialSwitch>(R.id.swPanic).isChecked
        val runtime =
            com.assistant.RuntimeCoordinator.runtimeState()
        val health =
            com.assistant.RuntimeHealthMonitor.runtimeHealthSnapshot()
        val frame =
            com.assistant.FrameAssembler.frameRuntimeSnapshot()
        val decision =
            com.assistant.RuntimeDecisionLoop.decisionRuntimeSnapshot()
        val contributions =
            com.assistant.execution.ContributionRegistry.contributionRuntimeSnapshot()
        val execution =
            com.assistant.GestureExecutionAuthority
                .executionRuntimeSnapshot()
        val registry =
            com.assistant.runtime.GameplayEngineRegistry
                .registryRuntimeSnapshot()

        grid.removeAllViews()
        with(ControlRoomBulbs) {
            grid.addView(sectionHeader(this@SmartAssistControlRoomActivity, "RUNTIME"))
            renderBulbSection(grid, listOf(
                BulbEntry("Smart Assist", if (enabled) BulbState.GREEN else BulbState.YELLOW, if (enabled) "On" else "Off"),
                BulbEntry("Panic mode", if (panic) BulbState.YELLOW else BulbState.GREEN, if (panic) "On" else "Off"),
                boolBulb(runtime, "runtimeReady", "Runtime ready"),
                boolBulb(runtime, "permissionsVerified", "Permissions"),
                boolBulb(runtime, "accessibilityReady", "Accessibility"),
                boolBulb(runtime, "captureReady", "Capture"),
                boolBulb(runtime, "boosterReady", "Booster"),
                countBulb(runtime, "busPending", "Bus pending")
            ))
            grid.addView(sectionHeader(this@SmartAssistControlRoomActivity, "HEALTH"))
            renderBulbSection(grid, listOf(
                boolBulb(health, "accessibilityAlive", "Accessibility", "Alive", "Dead"),
                boolBulb(health, "overlayAlive", "Overlay", "Alive", "Dead"),
                boolBulb(health, "frameAlive", "Frame", "Alive", "Dead"),
                boolBulb(health, "decisionAlive", "Decision", "Alive", "Dead"),
                boolBulb(health, "busAlive", "Bus", "Alive", "Dead"),
                boolBulb(health, "dispatchAlive", "Dispatch", "Alive", "Dead"),
                boolBulb(health, "gameplayAlive", "Gameplay", "Alive", "Dead"),
                boolBulb(health, "boosterAlive", "Booster", "Alive", "Dead"),
                issuesBulb(health),
                countBulb(health, "evaluations", "Health checks")
            ))
            grid.addView(sectionHeader(this@SmartAssistControlRoomActivity, "FRAME"))
            renderBulbSection(grid, listOf(
                boolBulb(frame, "trusted", "Trusted", "Yes", "No"),
                BulbEntry("Has ball", if (bool(frame, "hasBall")) BulbState.YELLOW else BulbState.GREEN, if (bool(frame, "hasBall")) "Yes" else "No"),
                countBulb(frame, "viableLanes", "Lanes"),
                BulbEntry("Confidence", if (bool(frame, "trusted")) BulbState.GREEN else BulbState.YELLOW, text(frame, "confidence"))
            ))
            grid.addView(sectionHeader(this@SmartAssistControlRoomActivity, "DECISION"))
            renderBulbSection(grid, listOf(
                countBulb(decision, "decisions", "Decisions"),
                countBulb(decision, "routed", "Routed"),
                countBulb(decision, "idleUntrusted", "Idle untrusted"),
                countBulb(decision, "idleNoContribution", "Idle no contribution"),
                countBulb(decision, "gateBlocked", "Gate blocked"),
                countBulb(decision, "staleFrame", "Stale frame"),
                countBulb(decision, "routeRejected", "Route rejected"),
                countBulb(decision, "chooseNull", "Choose null"),
                countBulb(decision, "moveHeldNoTouch", "Move held"),
                BulbEntry("Last action", if (count(decision, "routed") > 0) BulbState.GREEN else BulbState.YELLOW, text(decision, "lastAction")),
                BulbEntry("Last weight", if (count(decision, "routed") > 0) BulbState.GREEN else BulbState.YELLOW, text(decision, "lastWeight"))
            ))
            grid.addView(sectionHeader(this@SmartAssistControlRoomActivity, "CONTRIBUTIONS"))
            renderBulbSection(grid, listOf(
                countBulb(contributions, "offered", "Offered"),
                countBulb(contributions, "drained", "Drained"),
                countBulb(contributions, "pending", "Pending")
            ))
            grid.addView(sectionHeader(this@SmartAssistControlRoomActivity, "EXECUTION"))
            renderBulbSection(grid, listOf(
                countBulb(execution, "requested", "Requested"),
                countBulb(execution, "accepted", "Accepted"),
                failBulb(execution, "failed", "Failed")
            ))
            grid.addView(sectionHeader(this@SmartAssistControlRoomActivity, "REGISTRY"))
            renderBulbSection(grid, listOf(
                countBulb(registry, "engines", "Engines"),
                countBulb(registry, "collectCycles", "Collect cycles"),
                countBulb(registry, "contributed", "Contributed"),
                countBulb(registry, "generation", "Generation"),
                countBulb(registry, "collisions", "Collisions")
            ))
            grid.addView(monoBlock(this@SmartAssistControlRoomActivity, "Registry Names=${text(registry, "names")}"))
            grid.addView(monoBlock(this@SmartAssistControlRoomActivity, "Registry Capabilities=${text(registry, "capabilities")}"))
        }
    }

    private fun refreshEngineStatus() {
        // No66-D29: was six calls doing three idempotent jobs —
        // synchronizeExistingPerformanceEngines() just delegates to
        // synchronizeRuntimePipeline(), and enableVisualization()
        // already enables diagnostics. Same end state, one call per job.
        // Item 13 (half-effect): the enableDiagnostics() call is gone —
        // the overlay toggles it enabled were deleted (no draw code ever
        // read them), so there is nothing left to enable.
        runCatching {
            RuntimePerformanceCoordinator.synchronizeRuntimePipeline()
        }

        runCatching {
            RuntimeVisualizationRegistry.enableVisualization()
        }
    }

}

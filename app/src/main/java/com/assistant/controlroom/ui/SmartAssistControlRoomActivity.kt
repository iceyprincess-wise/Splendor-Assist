package com.assistant.controlroom.ui

import android.os.Bundle
import android.widget.Button
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.assistant.overlay.R


import com.assistant.SmartAssistRepository
import com.assistant.overlay.bridge.SmartAssistControlRoomBridge
import com.assistant.RuntimePerformanceCoordinator
import com.assistant.RuntimeVisualizationRegistry
import com.assistant.RuntimeOverlayHub








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

        // No66-D26/D27: refreshRuntime/refreshMetrics are member functions
        // now (below) so onResume() can call them too; the sliders no
        // longer rebuild the metrics on every drag tick.
        refreshRuntime()
        refreshMetrics()

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

            refreshRuntime()
            refreshMetrics()

            finish()
        }

        enabled.setOnCheckedChangeListener { _, _ -> refreshRuntime() }
        panic.setOnCheckedChangeListener { _, _ -> refreshRuntime() }
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

        refreshRuntime()
        refreshMetrics()
    }

    // No66-D26/D27: member functions (were local funs in onCreate, so
    // onResume could not call them). The metrics no longer rebuild on
    // every slider tick; they refresh here and on save instead.
    private fun refreshRuntime() {
        val enabled = findViewById<MaterialSwitch>(R.id.swEnabled)
        val panic = findViewById<MaterialSwitch>(R.id.swPanic)
        findViewById<TextView>(R.id.tvRuntime).text =
            "Enabled=${enabled.isChecked}\n" +
            "Panic=${panic.isChecked}"
    }

    private fun refreshMetrics() {
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

        findViewById<TextView>(R.id.tvMetrics).text =
            buildString {
                append("RuntimeReady=${runtime["runtimeReady"]}\n")
                append("Permissions=${runtime["permissionsVerified"]} Accessibility=${runtime["accessibilityReady"]} Capture=${runtime["captureReady"]} Booster=${runtime["boosterReady"]}\n")
                append("Health=${health["degradedReasons"]}\n")
                append("Frame Trusted=${frame["trusted"]} Ball=${frame["hasBall"]} Lanes=${frame["viableLanes"]} Conf=${frame["confidence"]}\n")
                append("Decision Routed=${decision["routed"]} IdleUntrusted=${decision["idleUntrusted"]} IdleNoContribution=${decision["idleNoContribution"]} GateBlocked=${decision["gateBlocked"]} StaleFrame=${decision["staleFrame"]} Settings=${decision["settingsExit"]} RouteRejected=${decision["routeRejected"]} ChooseNull=${decision["chooseNull"]} MoveHeldNoTouch=${decision["moveHeldNoTouch"]}\n")
                append("LastAction=${decision["lastAction"]} Weight=${decision["lastWeight"]}\n")
                append("Contributions Offered=${contributions["offered"]} Drained=${contributions["drained"]} Pending=${contributions["pending"]}\n")
                append("Execution Requested=${execution["requested"]} Accepted=${execution["accepted"]} Failed=${execution["failed"]}\n")
                append("Registry Engines=${registry["engines"]} CollectCycles=${registry["collectCycles"]}\n")
                append("Registry Names=${registry["names"]}\n")
                append("Registry Contributed=${registry["contributed"]}")
            }
    }

    private fun refreshEngineStatus() {
        // No66-D29: was six calls doing three idempotent jobs —
        // synchronizeExistingPerformanceEngines() just delegates to
        // synchronizeRuntimePipeline(), enableVisualization() already
        // enables diagnostics, and enableDiagnostics() already enables
        // all overlays. Same end state, one call per job.
        runCatching {
            RuntimePerformanceCoordinator.synchronizeRuntimePipeline()
        }

        runCatching {
            RuntimeVisualizationRegistry.enableVisualization()
        }

        runCatching {
            RuntimeOverlayHub.enableDiagnostics()
        }
    }

}
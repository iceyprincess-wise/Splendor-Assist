package com.assistant.controlroom.ui

import android.os.Bundle
import android.widget.Button
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.assistant.overlay.R
import com.assistant.adapter.smartassist.SmartAssistConfiguration
import com.assistant.adapter.smartassist.SmartAssistMetrics
import com.assistant.adapter.smartassist.SmartAssistRepository
import com.assistant.adapter.smartassist.RuntimePerformanceCoordinator
import com.assistant.adapter.smartassist.RuntimeDiagnosticsRegistry
import com.assistant.adapter.smartassist.RuntimeVisualizationRegistry
import com.assistant.adapter.smartassist.RuntimeOverlayHub
import com.assistant.adapter.smartassist.VisionOverlayRegistry
import com.assistant.adapter.smartassist.FPSMonitor
import com.assistant.adapter.smartassist.VisionLatencyMonitor
import com.assistant.adapter.smartassist.ConfidenceHeatmap
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource

class SmartAssistControlRoomActivity : AppCompatActivity() {

    
override fun onCreate(savedInstanceState: Bundle?) {


        super.onCreate(savedInstanceState)
setContentView(R.layout.activity_smartassist_control_room)

        val repo = SmartAssistRepository(this)

        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )

        val config = SmartAssistRepository.configuration()

        // PHASE10_SMARTASSIST_REPO_RESTORE_FIX_MARKER
    // PHASE10_SMARTASSIST_PERSISTENCE_FINAL_MARKER
    // PHASE10_SMARTASSIST_PERSISTENCE_MARKER

        val enabled = findViewById<MaterialSwitch>(R.id.swEnabled)
        val panic = findViewById<MaterialSwitch>(R.id.swPanic)
        val pass = findViewById<Slider>(R.id.passSeek)
        val shot = findViewById<Slider>(R.id.shotSeek)
        val cross = findViewById<Slider>(R.id.crossSeek)

        enabled.isChecked = true
        panic.isChecked = SmartAssistRepository.panicActive()
        pass.value = config.passThreshold.toFloat()
        shot.value = config.shotThreshold.toFloat()
        cross.value = config.crossThreshold.toFloat()

        fun refreshRuntime() {
            findViewById<TextView>(R.id.tvRuntime).text =
                "Enabled=${enabled.isChecked}\n" +
                "Panic=${panic.isChecked}\n" +
                "Optimization=true"
        }

        fun refreshMetrics() {
            val gameplay = SmartAssistMetrics.gameplayDownstreamRuntimeSnapshot()
            val amplified = SmartAssistMetrics.gameplayAmplificationRuntimeSnapshot()
            val magneticFeet = SmartAssistMetrics.magneticFeetRuntimeSnapshot()
            val crossingLane = SmartAssistMetrics.crossingLaneRuntimeSnapshot()

            findViewById<TextView>(R.id.tvMetrics).text =
                "Submitted=${SmartAssistMetrics.requestsSubmitted.get()}\n" +
                "Executed=${SmartAssistMetrics.requestsExecuted.get()}\n" +
                "Trajectory=${SmartAssistMetrics.trajectoryProduced.get()}\n" +
                "Gameplay=${gameplay["source"]} Seq=${gameplay["sequence"]} Active=${gameplay["active"]}\n" +
                "DecisionCycles=${amplified["decisionCycles"]} Authority=${amplified["lastAuthority"]}\n" +
                "MagneticFeet Seq=${magneticFeet["sequence"]} Touch=${magneticFeet["touchRetention"]} " +
                "Control=${magneticFeet["possessionControl"]}\n" +
                "Crossing Seq=${crossingLane["sequence"]} Lanes=${crossingLane["laneCount"]} " +
                "Viable=${crossingLane["viableLaneCount"]} Best=${crossingLane["bestConfidence"]}"
        }

        refreshRuntime()
        refreshMetrics()


        // PHASE10_MASTER_AUTHORITY_ACTIVITY_MARKER

        val authoritySlider =
            findViewById<com.google.android.material.slider.Slider>(R.id.authoritySeek)

        val authorityLabel =
            findViewById<TextView>(R.id.tvAuthorityValue)

        authoritySlider.value=config.authority.toFloat()

        authorityLabel.text =
            "${config.authority}% (${RuntimePerformanceCoordinator.runtimeAuthority()} Runtime)"

        authoritySlider.addOnChangeListener { _, value, _ ->

            repo.updateAuthority(value.toInt())

            RuntimePerformanceCoordinator.updateAuthority(
                value.toInt()
            )

            authorityLabel.text =
                "${value.toInt()}% (${RuntimePerformanceCoordinator.runtimeAuthority()} Runtime)"

            refreshEngineStatus()
        }


        findViewById<Button>(R.id.btnSave).setOnClickListener {

            repo.updateEnabled(enabled.isChecked)

            if (enabled.isChecked) {
            }
            repo.updatePanicMode(panic.isChecked)
            repo.updateThresholds(
                pass.value.toInt(),
                shot.value.toInt(),
                cross.value.toInt()
            )

            Toast.makeText(
                this,
                "Smart Assist settings saved",
                Toast.LENGTH_SHORT
            ).show()

            refreshRuntime()
            refreshMetrics()

            Toast.makeText(
                this,
                "Smart Assist saved",
                Toast.LENGTH_SHORT
            ).show()

            finish()
        }

        enabled.setOnCheckedChangeListener { _, _ -> refreshRuntime() }
        panic.setOnCheckedChangeListener { _, _ -> refreshRuntime() }
        pass.addOnChangeListener { _, _, _ ->
            refreshMetrics()
        }
        shot.addOnChangeListener { _, _, _ ->
            refreshMetrics()
        }
        cross.addOnChangeListener { _, _, _ ->
            refreshMetrics()
        }
    }

    override fun onResume() {
        super.onResume()

        findViewById<MaterialSwitch>(R.id.swEnabled).isChecked =
            SmartAssistRepository.enabled()

        findViewById<MaterialSwitch>(R.id.swPanic).isChecked =
            SmartAssistRepository.panicActive()
    }

    // PHASE10_CONTROLROOM_RUNTIME_MARKER

    

    
    // PHASE10_ENGINE_STATUS_REFRESH_MARKER

    private fun refreshEngineStatus() {

        runCatching {
            RuntimePerformanceCoordinator.synchronizeExistingPerformanceEngines()
        }

        runCatching {
            RuntimePerformanceCoordinator.synchronizeRuntimePipeline()
        }

        runCatching {
            RuntimeDiagnosticsRegistry.enableRuntimeDiagnostics()
        }

        runCatching {
            RuntimeVisualizationRegistry.enableVisualization()
        }

        runCatching {
            VisionOverlayRegistry.enableAll()
        }

        runCatching {
            RuntimeOverlayHub.enableDiagnostics()
        }
    }

private fun refreshRuntimeStatus() {
        refreshEngineStatus()

        runCatching {
            RuntimePerformanceCoordinator.synchronizeExistingPerformanceEngines()
        }

        runCatching {
            RuntimePerformanceCoordinator.synchronizeRuntimePipeline()
        }

        runCatching {
            RuntimeDiagnosticsRegistry.refresh()
        }

        runCatching {
            RuntimeVisualizationRegistry.refresh()
        }

        runCatching {
            VisionOverlayRegistry.enableAll()
        }

        runCatching {
            RuntimeOverlayHub.enableDiagnostics()
        }

        runCatching {
            FPSMonitor.refresh()
        }

        runCatching {
            VisionLatencyMonitor.refresh()
        }

        runCatching {
            ConfidenceHeatmap.refresh()
        }
    }
}
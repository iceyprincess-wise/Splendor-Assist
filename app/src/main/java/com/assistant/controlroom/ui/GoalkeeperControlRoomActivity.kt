package com.assistant.controlroom.ui
import com.assistant.adapter.smartassist.SmartAssistRepository

import android.os.Bundle
import android.widget.Button
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.assistant.overlay.R
import com.assistant.overlay.repository.GoalkeeperRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.assistant.adapter.smartassist.RuntimePerformanceCoordinator
import com.assistant.adapter.smartassist.RuntimeDiagnosticsRegistry
import com.assistant.adapter.smartassist.RuntimeVisualizationRegistry
import com.assistant.adapter.smartassist.RuntimeOverlayHub
import com.assistant.adapter.smartassist.VisionOverlayRegistry
import com.assistant.adapter.smartassist.FPSMonitor
import com.assistant.adapter.smartassist.VisionLatencyMonitor
import com.assistant.adapter.smartassist.ConfidenceHeatmap

class GoalkeeperControlRoomActivity : AppCompatActivity() {
    
    private lateinit var repository: GoalkeeperRepository
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
setContentView(R.layout.activity_goalkeeper_control_room)
        
        repository = GoalkeeperRepository(this)
        
        val switchEnabled = findViewById<MaterialSwitch>(R.id.switch_enabled)
        val switchAggressive = findViewById<MaterialSwitch>(R.id.switch_aggressive)
        val seekPositioning = findViewById<Slider>(R.id.seekbar_positioning)
        val seekReactions = findViewById<Slider>(R.id.seekbar_reactions)
        val textPositioning = findViewById<TextView>(R.id.text_positioning_value)
        val textReactions = findViewById<TextView>(R.id.text_reactions_value)
        val textStatus = findViewById<TextView>(R.id.text_status)
        val buttonSave = findViewById<Button>(R.id.button_save)
        
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

    override fun onResume() {
        super.onResume()

        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )
    }


}
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
import com.assistant.overlay.repository.InterceptionRepository
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

class InterceptionControlRoomActivity : AppCompatActivity() {
    
    private lateinit var repository: InterceptionRepository
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
setContentView(R.layout.activity_interception_control_room)
        
        repository = InterceptionRepository(this)
        
        val switchEnabled = findViewById<MaterialSwitch>(R.id.switch_enabled)
        val switchAutoIntercept = findViewById<MaterialSwitch>(R.id.switch_auto_intercept)
        val seekAwareness = findViewById<Slider>(R.id.seekbar_awareness)
        val seekPrediction = findViewById<Slider>(R.id.seekbar_prediction)
        val textAwareness = findViewById<TextView>(R.id.text_awareness_value)
        val textPrediction = findViewById<TextView>(R.id.text_prediction_value)
        val textStatus = findViewById<TextView>(R.id.text_status)
        val buttonSave = findViewById<Button>(R.id.button_save)
        
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
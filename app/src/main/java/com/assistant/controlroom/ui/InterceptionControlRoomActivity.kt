package com.assistant.controlroom.ui

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.assistant.overlay.R
import com.assistant.overlay.repository.InterceptionRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InterceptionControlRoomActivity : AppCompatActivity() {
    
    private lateinit var repository: InterceptionRepository
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_interception_control_room)
        
        repository = InterceptionRepository(this)
        
        val switchEnabled = findViewById<Switch>(R.id.switch_enabled)
        val switchAutoIntercept = findViewById<Switch>(R.id.switch_auto_intercept)
        val seekAwareness = findViewById<SeekBar>(R.id.seekbar_awareness)
        val seekPrediction = findViewById<SeekBar>(R.id.seekbar_prediction)
        val textAwareness = findViewById<TextView>(R.id.text_awareness_value)
        val textPrediction = findViewById<TextView>(R.id.text_prediction_value)
        val textStatus = findViewById<TextView>(R.id.text_status)
        val buttonSave = findViewById<Button>(R.id.button_save)
        
        lifecycleScope.launch {
            repository.state.collectLatest { state ->
                switchEnabled.isChecked = state.enabled
                switchAutoIntercept.isChecked = state.autoIntercept
                seekAwareness.progress = state.awareness
                seekPrediction.progress = state.prediction
                textAwareness.text = "${state.awareness}%"
                textPrediction.text = "${state.prediction}%"
                textStatus.text = if (state.enabled) "ACTIVE" else "INACTIVE"
            }
        }
        
        seekAwareness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textAwareness.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                repository.updateAwareness(seekBar?.progress ?: 50)
            }
        })
        
        seekPrediction.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textPrediction.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                repository.updatePrediction(seekBar?.progress ?: 50)
            }
        })
        
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
}

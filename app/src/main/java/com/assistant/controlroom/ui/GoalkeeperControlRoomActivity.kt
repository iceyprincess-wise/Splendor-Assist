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
import com.assistant.overlay.repository.GoalkeeperRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GoalkeeperControlRoomActivity : AppCompatActivity() {
    
    private lateinit var repository: GoalkeeperRepository
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_goalkeeper_control_room)
        
        repository = GoalkeeperRepository(this)
        
        val switchEnabled = findViewById<Switch>(R.id.switch_enabled)
        val switchAggressive = findViewById<Switch>(R.id.switch_aggressive)
        val seekPositioning = findViewById<SeekBar>(R.id.seekbar_positioning)
        val seekReactions = findViewById<SeekBar>(R.id.seekbar_reactions)
        val textPositioning = findViewById<TextView>(R.id.text_positioning_value)
        val textReactions = findViewById<TextView>(R.id.text_reactions_value)
        val textStatus = findViewById<TextView>(R.id.text_status)
        val buttonSave = findViewById<Button>(R.id.button_save)
        
        lifecycleScope.launch {
            repository.state.collectLatest { state ->
                switchEnabled.isChecked = state.enabled
                switchAggressive.isChecked = state.aggressiveMode
                seekPositioning.progress = state.positioning
                seekReactions.progress = state.reactions
                textPositioning.text = "${state.positioning}%"
                textReactions.text = "${state.reactions}%"
                textStatus.text = if (state.enabled) "ACTIVE" else "INACTIVE"
            }
        }
        
        seekPositioning.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textPositioning.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                repository.updatePositioning(seekBar?.progress ?: 50)
            }
        })
        
        seekReactions.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textReactions.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                repository.updateReactions(seekBar?.progress ?: 50)
            }
        })
        
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
}

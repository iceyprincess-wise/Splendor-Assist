package com.assistant.controlroom.ui

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.assistant.overlay.R
import com.assistant.adapter.smartassist.SmartAssistConfiguration
import com.assistant.adapter.smartassist.SmartAssistMetrics
import com.assistant.adapter.smartassist.SmartAssistRepository

class SmartAssistControlRoomActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smartassist_control_room)

        val config = SmartAssistRepository.configuration()

        val enabled = findViewById<Switch>(R.id.swEnabled)
        val panic = findViewById<Switch>(R.id.swPanic)
        val pass = findViewById<SeekBar>(R.id.passSeek)
        val shot = findViewById<SeekBar>(R.id.shotSeek)
        val cross = findViewById<SeekBar>(R.id.crossSeek)

        enabled.isChecked = true
        panic.isChecked = SmartAssistRepository.panicActive()
        pass.progress = config.passThreshold
        shot.progress = config.shotThreshold
        cross.progress = config.crossThreshold

        fun refreshRuntime() {
            findViewById<TextView>(R.id.tvRuntime).text =
                "Enabled=${enabled.isChecked}\n" +
                "Panic=${panic.isChecked}\n" +
                "Optimization=${true}"
        }

        fun refreshMetrics() {
            findViewById<TextView>(R.id.tvMetrics).text =
                "Submitted=${SmartAssistMetrics.requestsSubmitted.get()}\n" +
                "Executed=${SmartAssistMetrics.requestsExecuted.get()}\n" +
                "Trajectory=${SmartAssistMetrics.trajectoryProduced.get()}"
        }

        refreshRuntime()
        refreshMetrics()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            
            val repo = SmartAssistRepository(this)

            repo.updateEnabled(enabled.isChecked)
            repo.updatePanicMode(panic.isChecked)
            repo.updateThresholds(
                pass.progress,
                shot.progress,
                cross.progress
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
        pass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refreshMetrics()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        shot.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refreshMetrics()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        cross.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refreshMetrics()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }
}

package com.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import android.widget.TextView
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            
            // BULLETPROOFING: Double-bind both Static Memory and IPC Parceling
            EngineData.code = result.resultCode
            EngineData.intent = result.data
            
            EngineData.code = result.resultCode; EngineData.intent = result.data; EngineData.code = result.resultCode; EngineData.intent = result.data; val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("CROSS_PROCESS_CODE", result.resultCode)
                putExtra("CROSS_PROCESS_DATA", result.data)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Secure Engine IPC Bridge Linked", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Engine Authorization Denied.", Toast.LENGTH_LONG).show()
        }
    }


override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(this))
    setContentView(com.assistant.overlay.R.layout.activity_main)

    // [IGNITION INJECTION: DO NOT REMOVE]
    com.assistant.DashboardInjector.attach(this)

    projectionManager =
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    refreshAdapterHealth()

    findViewById<Button>(com.assistant.overlay.R.id.btnStartEngine)
        .setOnClickListener {
            checkBatteryAndProceed()
        }

    findViewById<Button>(com.assistant.overlay.R.id.btnViewLogs)
        .setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
}

private fun refreshAdapterHealth() {

    val inputView =
        findViewById<TextView>(com.assistant.overlay.R.id.tvInputAdapter)

    val lmkView =
        findViewById<TextView>(com.assistant.overlay.R.id.tvLmkAdapter)

    val netView =
        findViewById<TextView>(com.assistant.overlay.R.id.tvNetAdapter)

    val syncView =
        findViewById<TextView>(com.assistant.overlay.R.id.tvSyncAdapter)

    AdapterHealthRegistry.get("adapter_input")?.let {
        inputView.text = "Input Adapter: ${AdapterHealthRegistry.effectiveStatus("adapter_input")}"
    }

    AdapterHealthRegistry.get("adapter_lmk")?.let {
        lmkView.text = "LMK Adapter: ${AdapterHealthRegistry.effectiveStatus("adapter_lmk")}"
    }

    AdapterHealthRegistry.get("adapter_net")?.let {
        netView.text = "Network Adapter: ${AdapterHealthRegistry.effectiveStatus("adapter_net")}"
    }

    AdapterHealthRegistry.get("adapter_sync")?.let {
        syncView.text = "Sync Adapter: ${AdapterHealthRegistry.effectiveStatus("adapter_sync")}"
    }
}

    private fun checkBatteryAndProceed() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                checkOverlayAndProceed()
            }
        } catch (e: Exception) {
            checkOverlayAndProceed()
        }
    }

    private fun checkOverlayAndProceed() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        } else {
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }
}


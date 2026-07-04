package com.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.ComponentName
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.Manifest
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.assistant.controlroom.AdapterControlRoomRegistry
import com.assistant.controlroom.ui.FutureRoomsActivity
import com.assistant.controlroom.ui.GoalkeeperControlRoomActivity
import com.assistant.controlroom.ui.InterceptionControlRoomActivity
import com.assistant.controlroom.ui.SmartAssistControlRoomActivity
import com.assistant.overlay.ui.AnalyticsTheaterActivity
import com.assistant.adapter.smartassist.SmartAssistRepository
import com.assistant.adapter.smartassist.RuntimePerformanceCoordinator
import com.assistant.adapter.smartassist.RuntimeDiagnosticsRegistry
import com.assistant.adapter.smartassist.RuntimeVisualizationRegistry
import com.assistant.adapter.smartassist.RuntimeOverlayHub
import com.assistant.adapter.smartassist.VisionOverlayRegistry
import com.assistant.adapter.smartassist.FPSMonitor
import com.assistant.adapter.smartassist.VisionLatencyMonitor
import com.assistant.adapter.smartassist.ConfidenceHeatmap
import com.assistant.compliance.ComplianceState
import com.assistant.LogActivity

class MainActivity : AppCompatActivity() {

    private enum class PermissionStage {
        BATTERY,
        AUTOSTART_WAIT,
        ACCESSIBILITY,
        OVERLAY,
        NOTIFICATION,
        MEDIA_PROJECTION,
        COMPLETE
    }

    private var permissionStage = PermissionStage.BATTERY



    // PHASE17_PERMISSION_PIPELINE
    private var permissionPipelineStarted = false
    private var permissionPipelineActive = false


    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (
                result.resultCode == Activity.RESULT_OK &&
                result.data != null
            ) {

                permissionPipelineActive = false

                EngineData.code = result.resultCode
                EngineData.intent = result.data

                val serviceIntent =
                    Intent(this, OverlayService::class.java).apply {
                        putExtra("CROSS_PROCESS_CODE", result.resultCode)
                        putExtra("CROSS_PROCESS_DATA", result.data)
                    }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                Toast.makeText(
                    this,
                    "Engine Linked",
                    Toast.LENGTH_LONG
                ).show()

            } else {

                Toast.makeText(
                    this,
                    "MediaProjection permission cancelled",
                    Toast.LENGTH_SHORT
                ).show()

            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler(
            GlobalCrashHandler(this)
        )

        setContentView(
            com.assistant.overlay.R.layout.activity_main
        )

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        bindHomeButtons()
        refreshRoomBulbs()
        refreshRuntimeDashboard()
        updateRuntimeDashboardCards()
    }

    

    
    // PHASE10_NAVIGATION_RUNTIME_MARKER

// PHASE10_LIVE_RUNTIME_METRICS_MARKER
    private fun updateLiveRuntimeMetrics() {
        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtRuntimeStatus
            ).text = "Runtime • Active"
        }

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtVisionStatus
            ).text = "Vision • Ready"
        }

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtDiagnosticsStatus
            ).text = "Diagnostics • Online"
        }
    }


    private fun synchronizeApplicationRuntime()
{
        updateLiveRuntimeMetrics()

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

        if (permissionPipelineActive) {

            when (permissionStage) {

                PermissionStage.AUTOSTART_WAIT ->
                    showAutoStartConfirmation()

                else ->
                    checkBatteryAndProceed()
            }
        }

        synchronizeApplicationRuntime()
        super.onResume()
        refreshRoomBulbs()
    }

    private fun bindHomeButtons() {
        findViewById<Button>(com.assistant.overlay.R.id.btnStartEngine).setOnClickListener {
            permissionPipelineStarted = true
            permissionPipelineActive = true
            checkBatteryAndProceed()
        }

        findViewById<Button>(com.assistant.overlay.R.id.btnViewLogs)
    .setOnClickListener {
        startActivity(
            Intent(
                this,
                LogActivity::class.java
            )
        )
    }

        findViewById<View>(com.assistant.overlay.R.id.cardSmartAssist).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    SmartAssistControlRoomActivity::class.java
                )
            )
        }

        findViewById<View>(com.assistant.overlay.R.id.cardGoalkeeper).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    GoalkeeperControlRoomActivity::class.java
                )
            )
        }

        findViewById<View>(com.assistant.overlay.R.id.cardInterception).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    InterceptionControlRoomActivity::class.java
                )
            )
        }

        fun openFutureRoom(label: String) {
            startActivity(
                Intent(this, FutureRoomsActivity::class.java).putExtra("room_label", label)
            )
        }

        findViewById<View>(com.assistant.overlay.R.id.cardOverlay).setOnClickListener { openFutureRoom("Overlay") }
        findViewById<View>(com.assistant.overlay.R.id.cardAccessibility).setOnClickListener { openFutureRoom("Accessibility") }
        findViewById<View>(com.assistant.overlay.R.id.cardNotifications).setOnClickListener { openFutureRoom("Notifications") }
        findViewById<View>(com.assistant.overlay.R.id.cardMediaProjection).setOnClickListener {
    startActivity(
        Intent(
            this,
            AnalyticsTheaterActivity::class.java
        )
    )
}
        findViewById<View>(com.assistant.overlay.R.id.cardDiagnostics).setOnClickListener { openFutureRoom("Diagnostics") }
        findViewById<View>(com.assistant.overlay.R.id.cardFutureRooms).setOnClickListener { openFutureRoom("Future Rooms") }
    }



    // PHASE10_DASHBOARD_MODERNIZATION_MARKER

    private fun refreshRuntimeDashboard() {
        runCatching { refreshRoomBulbs() }
        runCatching { refreshDashboardStatus() }
    }

    

    // PHASE10_RUNTIME_DASHBOARD_MARKER

    private fun updateRuntimeDashboardCards() {

        runCatching {
            refreshDashboardStatus()
        }

        runCatching {
            refreshRuntimeDashboard()
        }

        runCatching {
            RuntimePerformanceCoordinator.synchronizeExistingPerformanceEngines()
        }

        runCatching {
            RuntimePerformanceCoordinator.synchronizeRuntimePipeline()
        }
    }


    private fun refreshDashboardStatus() {
        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtRuntimeStatus
            ).text = "Runtime Online"
        }

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtVisionStatus
            ).text = "Vision Ready"
        }

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtDiagnosticsStatus
            ).text = "Diagnostics Active"
        }
    }


    private fun refreshRoomBulbs() {
        val smartReady =
            SmartAssistRepository.enabled()
        setBulb(
            com.assistant.overlay.R.id.tvSmartAssistBulb,
            com.assistant.overlay.R.id.tvSmartAssistState,
            smartReady,
            if (smartReady) "READY" else "LOCKED"
        )

        val goalkeeperReady =
            AdapterControlRoomRegistry.get("goalkeeper")?.enabled == true

        setBulb(
            com.assistant.overlay.R.id.tvGoalkeeperBulb,
            com.assistant.overlay.R.id.tvGoalkeeperState,
            goalkeeperReady,
            if (goalkeeperReady) "ACTIVE" else "OFF"
        )

        val interceptionReady =
            AdapterControlRoomRegistry.get("interception")?.enabled == true

        setBulb(
            com.assistant.overlay.R.id.tvInterceptionBulb,
            com.assistant.overlay.R.id.tvInterceptionState,
            interceptionReady,
            if (interceptionReady) "ACTIVE" else "OFF"
        )

        setBulb(
            com.assistant.overlay.R.id.tvFutureBulb,
            com.assistant.overlay.R.id.tvFutureState,
            true,
            "30 ROOMS"
        )
    }

    private fun setBulb(
        bulbId: Int,
        stateId: Int,
        active: Boolean,
        label: String
    ) {
        val bulb = findViewById<android.widget.TextView>(bulbId)
        val state = findViewById<android.widget.TextView>(stateId)

        bulb.setTextColor(
            if (active) android.graphics.Color.parseColor("#40E36A")
            else android.graphics.Color.parseColor("#D0D0D0")
        )
        state.text = label
    }



    // PHASE10_BATTERY_VENDOR_MARKER

    private fun launchIfExists(intent: Intent): Boolean {
        return if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            true
        } else {
            false
        }
    }

    private fun openBatteryOptimizationManager(): Boolean {

        permissionStage = PermissionStage.AUTOSTART_WAIT

        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()

        if (
            manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco") ||
            brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco")
        ) {

            val vendorIntents = listOf(

                Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                },

                Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.powercenter.PowerSettings"
                    )
                },

                Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:$packageName")
                }
            )

            vendorIntents.forEach {
                if (launchIfExists(it))
                    return true
            }
        }

        val fallback = listOf(

            Intent(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            ),

            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ),

            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )

        fallback.forEach {
            if (launchIfExists(it))
                return true
        }

        return false
    }


    private fun checkBatteryAndProceed() {

        if (!true) {
            Toast.makeText(
                this,
                "Set Your Optimization first",
                Toast.LENGTH_SHORT
            ).show()

            startActivity(
                Intent(
                    this,
                    SmartAssistControlRoomActivity::class.java
                )
            )
            return
        }

        try {

            if (ComplianceState.battery(this)) {
                checkAccessibilityAndProceed()
                return
            }

            if (!openBatteryOptimizationManager()) {
                checkAccessibilityAndProceed()
            }

        } catch (_: Exception) {
            checkAccessibilityAndProceed()
        }
    }
private fun checkAccessibilityAndProceed() {
        val enabled =
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

        val expectedService =
            "com.assistant.adapter.smartassist.SmartAssistAccessibilityEngine"

        if (
            !enabled.contains(expectedService, true) &&
            !enabled.contains(packageName, true)
        ) {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            return
        }

        checkNotificationAndProceed()
    }

    private fun checkNotificationAndProceed() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                9001
            )
            return
        }

        checkOverlayAndProceed()
    }

    private fun checkOverlayAndProceed() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            screenCaptureLauncher.launch(
                projectionManager.createScreenCaptureIntent()
            )
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 9001) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                checkOverlayAndProceed()
            }
        }
    }


    private fun showAutoStartConfirmation() {
    
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Background Auto Start")
                .setMessage(
                    "Before continuing, please confirm that you enabled:\n\n" +
                    "• Auto Start\n" +
                    "• Background Activity\n" +
                    "• No Restrictions (if available)"
                )
                .setCancelable(false)
                .setPositiveButton("Done") { _, _ ->
    
                    permissionStage = PermissionStage.ACCESSIBILITY
    
                    checkAccessibilityAndProceed()
    
                }
                .setNegativeButton("Open Settings Again") { _, _ ->
    
                    permissionStage = PermissionStage.AUTOSTART_WAIT
    
                    openBatteryOptimizationManager()
    
                }
                .show()
    
        }

}


package com.assistant

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

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (
                result.resultCode == Activity.RESULT_OK &&
                result.data != null
            ) {
                EngineData.code = result.resultCode
                EngineData.intent = result.data

                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    putExtra("CROSS_PROCESS_CODE", result.resultCode)
                    putExtra("CROSS_PROCESS_DATA", result.data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                Toast.makeText(this, "Engine Linked", Toast.LENGTH_LONG).show()
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
    }

    override fun onResume() {
        super.onResume()
        refreshRoomBulbs()
    }

    private fun bindHomeButtons() {
        findViewById<Button>(com.assistant.overlay.R.id.btnStartEngine).setOnClickListener {
            checkBatteryAndProceed()
        }

        findViewById<Button>(com.assistant.overlay.R.id.btnViewLogs).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    AnalyticsTheaterActivity::class.java
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
        findViewById<View>(com.assistant.overlay.R.id.cardMediaProjection).setOnClickListener { openFutureRoom("Media Projection") }
        findViewById<View>(com.assistant.overlay.R.id.cardDiagnostics).setOnClickListener { openFutureRoom("Diagnostics") }
        findViewById<View>(com.assistant.overlay.R.id.cardFutureRooms).setOnClickListener { openFutureRoom("Future Rooms") }
    }

    private fun refreshRoomBulbs() {
        val smartReady = true
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
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !pm.isIgnoringBatteryOptimizations(packageName)
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
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

        if (!enabled.contains(packageName, true)) {
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
}

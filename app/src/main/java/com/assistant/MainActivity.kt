package com.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.overlay.ui.AnalyticsTheaterActivity

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager:
        MediaProjectionManager

    private val dashboardHandler =
        Handler(Looper.getMainLooper())

    private val dashboardRefreshRunnable =
        object : Runnable {
            override fun run() {
                refreshAdapterHealth()
                dashboardHandler.postDelayed(this, 1000L)
            }
        }

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

                val serviceIntent =
                    Intent(
                        this,
                        OverlayService::class.java
                    )

                serviceIntent.putExtra(
                    "CROSS_PROCESS_CODE",
                    result.resultCode
                )

                serviceIntent.putExtra(
                    "CROSS_PROCESS_DATA",
                    result.data
                )

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
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler(
            GlobalCrashHandler(this)
        )

        setContentView(
            com.assistant.overlay.R.layout.activity_main
        )

        DashboardInjector.attach(this)

        projectionManager =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        refreshAdapterHealth()

        findViewById<Button>(
            com.assistant.overlay.R.id.btnStartEngine
        ).setOnClickListener {
            checkBatteryAndProceed()
        }

        findViewById<Button>(
            com.assistant.overlay.R.id.btnViewLogs
        ).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    AnalyticsTheaterActivity::class.java
                )
            )
        }
    }

    private fun refreshAdapterHealth() {

        val adapterList =
            AdapterHealthRegistry
                .getAll()
                .sortedBy { it.adapterName }
                .joinToString("\n") {
                    "${it.adapterName} : " +
                    AdapterHealthRegistry.effectiveStatus(it.adapterName)
                }

        findViewById<TextView>(
            com.assistant.overlay.R.id.tvAdapterList
        ).text = adapterList

        findViewById<TextView>(
            com.assistant.overlay.R.id.tvAdapterCount
        ).text =
            "Runtime Nodes : " +
            AdapterHealthRegistry.getAll().size
    }

    private fun checkBatteryAndProceed() {

        try {

            val pm =
                getSystemService(
                    Context.POWER_SERVICE
                ) as PowerManager

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M &&
                !pm.isIgnoringBatteryOptimizations(
                    packageName
                )
            ) {

                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )

            } else {

                checkOverlayAndProceed()
            }

        } catch (_: Exception) {

            checkOverlayAndProceed()
        }
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

    override fun onDestroy() {

        dashboardHandler.removeCallbacks(
            dashboardRefreshRunnable
        )

        super.onDestroy()
    }
}

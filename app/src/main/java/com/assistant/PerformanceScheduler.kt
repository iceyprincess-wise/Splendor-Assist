package com.assistant







import android.content.Context
import android.content.Intent
import android.content.IntentFilter










import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread





import android.os.PowerManager
import android.os.Process



import com.assistant.VisionTrust
import com.assistant.diagnostic.AdapterSignalBus































object PerformanceScheduler {
    private var schedulerThread: android.os.HandlerThread? = null
    private var schedulerHandler: android.os.Handler? = null

    fun start(context: android.content.Context) {
        if (schedulerThread != null) return
        schedulerThread = android.os.HandlerThread("PerfScheduler", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        schedulerHandler = android.os.Handler(schedulerThread!!.looper)

        schedulerHandler!!.post(object : Runnable {
            override fun run() {
                try {
                    val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val charging = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    val pct = if (scale > 0) (level * 100) / scale else level
                    com.assistant.diagnostic.AdapterSignalBus.publishBattery(pct, charging)
                    // SPLENDOR_P7_DEVICE_BATTERY_BEGIN
                    // Mirror into the cross-process device snapshot so
                    // main-process FilterEngine reads real battery values.
                    try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceBattery(pct, charging) } catch (_: Throwable) {}
                    // SPLENDOR_P7_DEVICE_BATTERY_END
                } catch (_: Throwable) {}
                val cadence = if (com.assistant.VisionTrust.isGameForeground()) 30_000L else 5_000L
                schedulerHandler?.postDelayed(this, cadence)
            }
        })

        schedulerHandler!!.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                        val status = pm?.currentThermalStatus ?: -1
                        com.assistant.diagnostic.AdapterSignalBus.publishThermal(status)
                        // SPLENDOR_P7_DEVICE_THERMAL_BEGIN
                        // Mirror into the cross-process device snapshot so
                        // main-process FilterEngine reads real thermal values.
                        try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceThermal(status) } catch (_: Throwable) {}
                        // SPLENDOR_P7_DEVICE_THERMAL_END
                    }
                } catch (_: Throwable) {}
                val cadence = if (com.assistant.VisionTrust.isGameForeground()) 60_000L else 30_000L
                schedulerHandler?.postDelayed(this, cadence)
            }
        }, 10000L)
    }

    fun stop() {
        schedulerHandler?.removeCallbacksAndMessages(null)
        schedulerThread?.quitSafely()
        schedulerThread = null
        schedulerHandler = null
    }
}

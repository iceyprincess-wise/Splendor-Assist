package com.assistant







import android.content.Context
import android.content.Intent
import android.content.IntentFilter










import android.os.BatteryManager
import android.os.Build
import android.os.Handler


import android.os.Looper



import android.os.PowerManager







































// --- MERGED: DisplayProfileEngine.kt ---

// V3 ADMIN-WIRED

/**
 * Reads the REAL panel refresh rate and derives the two budgets that matter:
 * vsync budget (panel) and game budget (eFootball locked at 30fps = 33.3ms).
 * Every other lag engine grades against these instead of guessing 60Hz.
 * V3: the game frame rate is admin-tunable for the day the game changes
 * its lock - no rebuild needed (invalid/0 safely falls back to 30).
 */

object CpuGovernorEngine {
    @Volatile var mode: String = "OBSERVING"
        private set
    @Volatile var thermalStatus: Int = -1
        private set
    @Volatile var batteryPercent: Int = -1
        private set
    @Volatile var batteryCharging: Boolean = false
        private set
    private var context: android.content.Context? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            val app = context
            if (app != null) {
                try {
                    val battery = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    if (battery != null) {
                        val level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                        val scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                        batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                        batteryCharging = battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) in
                            setOf(android.os.BatteryManager.BATTERY_STATUS_CHARGING, android.os.BatteryManager.BATTERY_STATUS_FULL)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val power = app.getSystemService(android.os.PowerManager::class.java)
                        thermalStatus = power?.currentThermalStatus ?: -1
                    } else {
                        thermalStatus = -1
                    }
                    mode = "OBSERVING"
                } catch (_: Throwable) {
                    mode = "OBSERVATION_UNAVAILABLE"
                }
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    fun start() {
        if (context == null) return
        handler.removeCallbacks(poll)
        poll.run()
    }

    fun start(appContext: android.content.Context) {
        context = appContext.applicationContext
        start()
    }

    fun stop() {
        handler.removeCallbacks(poll)
        context = null
        mode = "OBSERVING"
    }
}

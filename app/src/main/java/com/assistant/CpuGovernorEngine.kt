package com.assistant

import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.Choreographer
import com.assistant.VisionTrust
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import com.assistant.survival.ResourceBudgetRegistry
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random



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

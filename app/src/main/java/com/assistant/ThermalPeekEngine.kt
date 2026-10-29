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

object ThermalPeekEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
    private val POLL_MS: Long get() = 10_000L

    @Volatile private var pm: PowerManager? = null
    @Volatile private var running = false
    @Volatile var status = "?"; private set

    fun init(ctx: Context) {
        if (running) return
        running = true
        pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val t = Thread {
            var last = ""
            while (running) {
                try {
                    val s = if (Build.VERSION.SDK_INT >= 29) {
                        when (pm?.currentThermalStatus ?: -1) {
                            PowerManager.THERMAL_STATUS_NONE -> "NONE"
                            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                            else -> "?"
                        }
                    } else "UNSUPPORTED"
                    status = s
                    if (s != last) {
                        last = s
                        RuntimeLogger.log("THERMAL STATUS -> " + s, "LAGTHERM")
                    }
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-thermal-peek"; t.start()
    }

    fun stop() { running = false }
}

// --- NEW: GcStallEngine ---

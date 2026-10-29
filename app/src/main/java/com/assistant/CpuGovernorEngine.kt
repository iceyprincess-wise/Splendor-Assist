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

object CpuGovernorEngine {
    @Volatile private var running = false
    @Volatile var mode = "STARTING"; private set

    fun start() {
        if (running) return
        running = true
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
        val t = Thread {
            var logged = false
            while (running) {
                try {
                    var wrote = false
                    for (core in 6..7) {
                        val gov = java.io.File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_governor")
                        // SPLENDOR_SYSFS_ASYNC_FIX
                    if (gov.canWrite()) { try { gov.writeText("performance") } catch (_: Throwable) {}; wrote = true }
                    }
                    for (core in 0..5) {
                        val gov = java.io.File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_governor")
                        if (gov.canWrite()) gov.writeText("schedutil")
                    }
                    if (!logged) {
                        mode = if (wrote) "SYSFS_ACTIVE" else "PRIORITY_ONLY"
                        RuntimeLogger.log("CpuGovernor mode=$mode (A75=game A55=splendor)", "CPUGOV")
                        logged = true
                    }
                } catch (e: Throwable) {
                    if (!logged) {
                        mode = "PRIORITY_ONLY"
                        RuntimeLogger.log("CpuGovernor: priority-only (${e.javaClass.simpleName})", "CPUGOV")
                        logged = true
                    }
                }
                try { Thread.sleep(30_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-cpu-gov"; t.priority = Thread.MIN_PRIORITY; t.start()
        RuntimeLogger.log("CpuGovernorEngine started", "CPUGOV")
    }

    fun stop() { running = false; mode = "STOPPED" }
}

// --- MERGED: DisplayProfileEngine.kt ---

// V3 ADMIN-WIRED

/**
 * Reads the REAL panel refresh rate and derives the two budgets that matter:
 * vsync budget (panel) and game budget (eFootball locked at 30fps = 33.3ms).
 * Every other lag engine grades against these instead of guessing 60Hz.
 * V3: the game frame rate is admin-tunable for the day the game changes
 * its lock - no rebuild needed (invalid/0 safely falls back to 30).
 */

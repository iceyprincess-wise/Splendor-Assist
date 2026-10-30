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

object OomAdaptiveThrottleEngine {

    private const val OOM_HOSTILE_THRESHOLD = 10
    private const val POLL_MS = 500L
    private const val LOG_COOLDOWN_MS = 10_000L

    @Volatile private var running = false
    @Volatile var oomScore = 0; private set
    @Volatile var oomHostile = false; private set
    @Volatile var reapplyCount = 0; private set

    private var lastHostileLogMs = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            // Conditionlessly boost this polling thread immediately
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
            
            while (running) {
                try { poll() } catch (_: Throwable) {}
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-oom-throttle"
        t.priority = Thread.MAX_PRIORITY
        t.start()
        RuntimeLogger.log("OomAdaptiveThrottleEngine started (Hardworking Eliminator Mode - 500ms cycle)", "INPUT")
    }

    fun stop() { running = false }

    private fun poll() {
        val adj = readOomAdj(Process.myPid())
        oomScore = adj
        val hostile = adj >= OOM_HOSTILE_THRESHOLD
        val wasHostile = oomHostile
        oomHostile = hostile

        // CONDITIONLESS ACTIVE MITIGATION:
        // HyperOS throttles via cgroups even if thread priority isn't explicitly reset.
        // Force URGENT_AUDIO (-19) every cycle to prevent silent CPU starvation.
        try {
            val cur = Process.getThreadPriority(Process.myTid())
            if (cur > Process.THREAD_PRIORITY_URGENT_AUDIO) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                reapplyCount++
            }
        } catch (_: Throwable) {}

        if (hostile) {
            // Publish hostile signal so Queen Bee (SmartAssist) can drop non-essential UI work
            AdapterSignalBus.publishInput("OOM_HOSTILE", adj.toLong())

            val now = System.currentTimeMillis()
            if (!wasHostile || now - lastHostileLogMs > LOG_COOLDOWN_MS) {
                lastHostileLogMs = now
                RuntimeLogger.log(
                    "OOM_HOSTILE: adj=$adj — HyperOS throttling active. " +
                            "Priority reapply count=$reapplyCount",
                    "INPUT"
                )
            }
        } else {
            if (wasHostile) {
                RuntimeLogger.log("OOM_CLEAR: adj=$adj — throttle released", "INPUT")
                // Publish clear signal to restore full performance
                AdapterSignalBus.publishInput("OOM_CLEAR", adj.toLong())
            }
        }
    }

    private fun readOomAdj(pid: Int): Int = try {
        java.io.BufferedReader(java.io.FileReader("/proc/$pid/oom_score_adj"))
            .use { it.readLine()?.trim()?.toIntOrNull() ?: 0 }
    } catch (_: Throwable) { 0 }
}

/* ========
TouchQualityEngine
======== */
/**
 * TouchQualityEngine — HARDWORKING ELIMINATOR for Touch IRQ Stalls.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * A 5-second polling delay means touch unresponsiveness goes undetected for too long.
 * 
 * This engine now:
 * 1. Polls every 500ms for instant detection of kernel touch IRQ starvation.
 * 2. Detects stalls after just 2 cycles (1000ms) instead of 5 cycles (25 seconds).
 * 3. Conditionlessly publishes TOUCH_IRQ_STALL to the Queen Bee (SmartAssist) to trigger survival mode.
 * 4. Forcefully applies URGENT_AUDIO priority to fight HyperOS cgroup throttling during stalls.
 * 5. Removes redundant OOM reading (handled by OomAdaptiveThrottleEngine) to save CPU cycles.
 * 6. Fixes broken Regex for parsing /proc/interrupts.
 */

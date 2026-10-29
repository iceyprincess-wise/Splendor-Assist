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

object FramePacingEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val ALPHA: Float get() = 0.2f
    private val REPORT_EVERY_MS: Long get() = 20_000L
    private val STALL_MS: Float get() = 100f

    @Volatile private var running = false
    @Volatile var avgGapMs = 0f; private set
    @Volatile var jitterMs = 0f; private set
    @Volatile var stabilityPct = 100f; private set
    @Volatile var stallsPerMin = 0f; private set
    @Volatile var worstGapMs = 0f; private set
    @Volatile private var lastNanos = 0L
    @Volatile private var lastGap = 0f
    @Volatile private var frames = 0L
    @Volatile private var totalStalls = 0L

    // per-window vsync-multiple buckets: [1x, 2x, 3x, 4x+] + stalls
    private val bucket = LongArray(4)
    @Volatile private var winStalls = 0L
    @Volatile private var winFrames = 0L

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastNanos > 0L) {
                val gap = (frameTimeNanos - lastNanos) / 1_000_000f
                frames++; winFrames++
                val a = ALPHA
                avgGapMs = if (avgGapMs == 0f) gap else avgGapMs * (1 - a) + gap * a
                if (lastGap > 0f) {
                    val d = Math.abs(gap - lastGap)
                    jitterMs = jitterMs * (1 - a) + d * a
                }
                lastGap = gap
                if (gap > worstGapMs) worstGapMs = gap
                if (gap > STALL_MS) { winStalls++; totalStalls++ }
                else {
                    val v = DisplayProfileEngine.vsyncBudgetMs
                    val m = Math.round(gap / v).coerceIn(1, 4)
                    bucket[m - 1]++
                }
            }
            lastNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().postFrameCallback(callback)
        }
        val t = Thread {
            while (running) {
                val windowMs = REPORT_EVERY_MS
                try { Thread.sleep(windowMs) } catch (_: Throwable) { return@Thread }
                try {
                    val counted = bucket.sum().coerceAtLeast(1L)
                    val dominant = bucket.max()
                    stabilityPct = dominant * 100f / counted
                    val winMin = windowMs / 60000f
                    stallsPerMin = winStalls / winMin
                    val mix = bucket.joinToString("/") { (it * 100 / counted).toString() }
                    RuntimeLogger.log("frames=" + frames +
                        " mix=" + mix + "% stability=" + String.format("%.0f", stabilityPct) +
                        "% jitter=" + String.format("%.1f", jitterMs) +
                        "ms worst=" + String.format("%.0f", worstGapMs) +
                        "ms stalls/min=" + String.format("%.1f", stallsPerMin) +
                        " total=" + totalStalls, "LAGFRAME")
                    for (i in bucket.indices) bucket[i] = 0L
                    winStalls = 0L; winFrames = 0L; worstGapMs = 0f
                } catch (_: Throwable) { }
            }
        }
        t.isDaemon = true; t.name = "lag-frame-report"; t.start()
    }

    fun stop() {
        running = false
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }
}

// --- MERGED: LagVerdictEngine.kt ---

// V3 ADMIN-WIRED - every threshold live, publishes the Detector snapshot

/**
 * The judge: reads every lag measurement and names the state -
 * SMOOTH / JITTERY / CHOKING. A change must be seen on N consecutive polls
 * before it flips (no whipsawing on a single blip). V3: the poll rhythm and
 * EVERY threshold line are admin-tunable and re-read each poll; each poll
 * also publishes the full live lag snapshot the admin Detector reads.
 */

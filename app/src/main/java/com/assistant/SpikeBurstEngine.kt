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

object SpikeBurstEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val RECOVERY_WINDOW_MS: Long get() = 60_000L
    private val CLEAN_SAMPLES: Int get() = 2
    private val BURST_SAMPLES: Int get() = 5
    private val BURST_GAP_MS: Long get() = 200L
    private val CLEAN_MULT: Float get() = 1.5f

    @Volatile private var running = false
    @Volatile private var mapping = false
    @Volatile private var spikes = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    if (CongestionSentinelEngine.congested && !mapping) {
                        mapping = true
                        spikes++
                        val burst = if (BURST_SAMPLES < 1) 1 else BURST_SAMPLES
                        val gap = BURST_GAP_MS
                        val samples = ArrayList<Long>(burst)
                        repeat(burst) {
                            val s = NetProbeEngine.rawSample()
                            if (s >= 0) samples.add(s)
                            if (gap > 0) try { Thread.sleep(gap) } catch (_: Throwable) { }
                        }
                        if (samples.isNotEmpty()) {
                            RuntimeLogger.log("SPIKE MAPPED depth=" + samples.max() +
                                "ms floor=" + samples.min() + "ms n=" + spikes, "NETSPIKE")
                        }
                        // recovery watch: N consecutive clean samples = clear
                        val needClean = if (CLEAN_SAMPLES < 1) 1 else CLEAN_SAMPLES
                        var clean = 0
                        val t0 = System.currentTimeMillis()
                        while (running && clean < needClean &&
                               System.currentTimeMillis() - t0 < RECOVERY_WINDOW_MS) {
                            val s = NetProbeEngine.rawSample()
                            val cleanLine = (CarrierProfileEngine.baselineRttMs * CLEAN_MULT).toLong()
                            if (s in 0..cleanLine) clean++ else clean = 0
                            try { Thread.sleep(1000) } catch (_: Throwable) { break }
                        }
                        if (clean >= needClean) {
                            val dur = (System.currentTimeMillis() - t0) / 1000L
                            RuntimeLogger.log("SPIKE RECOVERED in " + dur + "s", "NETSPIKE")
                        }
                        mapping = false
                    }
                } catch (_: Throwable) { mapping = false }
                try { Thread.sleep(1000) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-spike"; t.start()
    }

    fun stop() { running = false }
}

/* ========
GestureTimingFeedbackEngine
======== */
/**
 * GestureTimingFeedbackEngine — actual gesture dispatch latency tracker & ELIMINATOR.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * 15fps = 66.6ms/frame. Gestures must execute within 1-2 frames to prevent input lag.
 *
 * This engine acts as a HARDWORKING ELIMINATOR:
 * - Conditionlessly boosts thread priority if dispatch exceeds 1 frame (66ms).
 * - Triggers BUS_STALE_PURGE if queue congestion exceeds 2 frames (120ms).
 * - Uses higher EWMA alpha for rapid reaction to sudden lag spikes.
 */

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

object NetProbeEngine {

    private val TARGETS = listOf("8.8.8.8" to 53, "1.1.1.1" to 53)
    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val FAST_MS: Long get() = 2000L
    private val CALM_MS: Long get() = 5000L
    private val TIMEOUT_MS: Int get() = 1200
    private val ALPHA: Float get() = 0.35f
    private val SAMPLES: Int get() = 3
    private val GAP_MS: Long get() = 60L
    private val DEGRADED_MULT: Float get() = 2f

    private val lock = Object()
    @Volatile private var running = false
    @Volatile var rtt = 0f; private set
    @Volatile var jitter = 0f; private set
    @Volatile var quality = "UNKNOWN"; private set
    @Volatile var lastRawMs = -1L; private set
    @Volatile private var probes = 0L
    @Volatile private var failures = 0L

    fun start(ctx: Context) {
        if (running) return
        running = true
        PerformanceTelemetryRegistry.initialize(ctx.applicationContext)
        val t = Thread {
            var tick = 0L
            while (running) {
                probeOnce()
                if (++tick % 6L == 0L) RuntimeLogger.log(summary(), "NETPROBE")
                val nap = if (quality == "GOOD") CALM_MS else FAST_MS
                try {
                    synchronized(lock) { lock.wait(if (nap > 0) nap else 1L) }
                } catch (_: InterruptedException) { }
            }
        }
        t.isDaemon = true; t.name = "net-probe"; t.start()
    }

    fun stop() { running = false; wake() }

    /** Link changed: wipe history and probe again NOW - no waiting out the nap. */
    fun onLinkChanged() {
        rtt = 0f; jitter = 0f; quality = "UNKNOWN"; lastRawMs = -1L
        wake()
        RuntimeLogger.log("link changed - history wiped, instant re-probe", "NETPROBE")
    }

    private fun wake() { synchronized(lock) { lock.notifyAll() } }

    /** single raw connect, shared with the burst mapper */
    fun rawSample(): Long {
        for ((host, port) in TARGETS) {
            val s = tcpRtt(host, port)
            if (s >= 0) return s
        }
        return -1L
    }

    private fun probeOnce() {
        val n = if (SAMPLES < 1) 1 else SAMPLES
        val gap = GAP_MS
        val samples = ArrayList<Long>(n)
        repeat(n) {
            val s = rawSample()
            if (s >= 0) samples.add(s)
            if (gap > 0) try { Thread.sleep(gap) } catch (_: Throwable) { }
        }
        probes++
        if (samples.isEmpty()) {
            failures++; quality = "BAD"; lastRawMs = -1L
        } else {
            samples.sort()
            val median = samples[samples.size / 2].toFloat()
            lastRawMs = median.toLong()
            if (rtt == 0f) rtt = median else {
                val diff = Math.abs(median - rtt)
                val a = ALPHA
                jitter = jitter * (1 - a) + diff * a
                rtt = rtt * (1 - a) + median * a
            }
            val base = CarrierProfileEngine.baselineRttMs
            val tol = CarrierProfileEngine.jitterTolMs
            quality = when {
                rtt <= base && jitter <= tol -> "GOOD"
                rtt <= base * DEGRADED_MULT -> "DEGRADED"
                else -> "BAD"
            }
        }
        PerformanceTelemetryRegistry.publishNet(
            rtt, jitter, quality, CarrierProfileEngine.current.name, NetworkStateEngine.transport)
        // Live ping quality was measured here but never published to the bus
        // that consumers read, so AdapterSignalBus.pingQuality stayed "UNKNOWN".
        com.assistant.diagnostic.AdapterSignalBus.publishPing(quality)
    }

    private fun tcpRtt(host: String, port: Int): Long = try {
        val t0 = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), TIMEOUT_MS) }
        (System.nanoTime() - t0) / 1_000_000L
    } catch (_: Throwable) { -1L }

    fun summary(): String =
        "rtt=" + String.format("%.0f", rtt) + "ms jitter=" + String.format("%.0f", jitter) +
        "ms " + quality + " loss=" + String.format("%.0f", PacketLossProbeEngine.lossPct) +
        "% carrier=" + CarrierProfileEngine.current.name +
        " probes=" + probes + " fail=" + failures
}
/* ======
NetProbeEngine Anchor
====== */

/* ========
NetworkStateEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V3: the OS now TELLS us the instant the network changes (default network
 * callback) instead of us noticing up to 10s late on a poll. On every change
 * the carrier profile re-selects, the probe engine wipes history and
 * re-probes immediately, and DNS re-warms - the whole stack re-learns the
 * new link in about one second. The poll remains only as a belt-and-braces
 * fallback and its cadence is admin-tunable.
 */

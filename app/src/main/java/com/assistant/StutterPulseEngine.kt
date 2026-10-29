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

object StutterPulseEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val BURST_MULT: Float get() = 4f
    private val MIN_FRAMES: Int get() = 3
    private val SLICE_MS: Long get() = 1000L
    private val PUBLISH_MS: Long get() = 5000L

    @Volatile private var running = false
    @Volatile var vsyncMs = 16.67f; private set
    @Volatile var panelHz = 60f; private set
    @Volatile private var lastNanos = 0L
    // current slice
    @Volatile private var sliceStart = 0L
    @Volatile private var sliceBad = 0
    @Volatile private var sliceWorst = 0f
    // outputs
    @Volatile var burstsPerMin = 0f; private set
    @Volatile var lastBurstWorstMs = 0f; private set
    @Volatile var lastBurstFrames = 0; private set
    @Volatile var lastBurstAtMs = 0L; private set
    @Volatile private var minuteBursts = 0
    @Volatile private var minuteStart = 0L

    fun detectPanel(ctx: Context) {
        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val hz = dm.displays.firstOrNull()?.refreshRate ?: 60f
            onPanelRate(hz)
        } catch (_: Throwable) { }
    }

    /** Called by PanelWatchEngine the instant the panel changes its rhythm. */
    fun onPanelRate(hz: Float) {
        if (hz <= 0f) return
        if (Math.abs(hz - panelHz) < 0.5f) return
        panelHz = hz
        vsyncMs = 1000f / hz
        RuntimeLogger.log("panel=" + String.format("%.0f", hz) +
            "Hz budget=" + String.format("%.1f", vsyncMs) + "ms (radar re-tuned live)", "STUTTER")
    }

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            if (lastNanos > 0L) {
                val gap = (t - lastNanos) / 1_000_000f
                val now = System.currentTimeMillis()
                if (sliceStart == 0L) { sliceStart = now; minuteStart = now }
                if (gap > vsyncMs * BURST_MULT) {
                    sliceBad++
                    if (gap > sliceWorst) sliceWorst = gap
                }
                val slice = if (SLICE_MS > 0) SLICE_MS else 1L
                if (now - sliceStart >= slice) {
                    val need = if (MIN_FRAMES < 1) 1 else MIN_FRAMES
                    if (sliceBad >= need) {       // a real burst, not one late frame
                        minuteBursts++
                        lastBurstWorstMs = sliceWorst
                        lastBurstFrames = sliceBad
                        lastBurstAtMs = now
                        BurstForensicsEngine.record(sliceBad, sliceWorst)
                    }
                    sliceBad = 0; sliceWorst = 0f; sliceStart = now
                }
                if (now - minuteStart >= 60_000L) {
                    burstsPerMin = minuteBursts.toFloat()
                    minuteBursts = 0; minuteStart = now
                }
            }
            lastNanos = t
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().postFrameCallback(callback)
        }
        // live readout for the admin Detector
        val t = Thread {
            while (running) {
                val nap = PUBLISH_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "stutter-publish"; t.start()
    }

    fun stop() {
        running = false
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }
}
/* ======
StutterPulseEngine Anchor
====== */


/* ========
ActionWindowEngine
======== */
// V3 INSTANT-REFLEX

/**
 * THE OUTPUT OF THE WHOLE STACK: one verdict, refreshed on an admin-tunable
 * cadence.
 *   GO      - link clean: full-speed decisions are safe
 *   CAUTION - degraded: prefer shorter, safer actions
 *   HOLD    - spike/loss in progress: worst moment to commit a long play
 * Published for the main runtime to read non-blocking. V3: reads the
 * override-aware wobble allowance.
 */

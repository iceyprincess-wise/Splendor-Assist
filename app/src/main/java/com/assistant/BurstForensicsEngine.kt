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

object BurstForensicsEngine {
    init {
        // PHASE3 FIX: Publish CALM immediately so bus is not stuck at UNKNOWN
        try { AdapterSignalBus.publishStutter("CALM") } catch (_: Throwable) {}
    }

    // OMEGA FIX: Catch silent micro-stutters that ruin input timing
    // 150ms is 9 dropped frames. 45ms is 3 dropped frames (noticeable input delay).
    private val SEIZURE_MS: Float get() = 45f
    private val OSC_BURSTS: Int get() = 2
    private val OSC_WINDOW_MS: Long get() = 10000L
    private val CALM_AFTER_MS: Long get() = 5000L
    private val DECAY_POLL_MS: Long get() = 2500L

    @Volatile var state = "CALM"; private set
    @Volatile private var decayRunning = false
    @Volatile private var lastLogMs = 0L
    private val recent = ArrayDeque<Long>()   // burst timestamps, last 60s

    fun bursts60s(): Int = synchronized(this) { recent.size }

    @Synchronized
    fun record(frames: Int, worstMs: Float) {
        val now = System.currentTimeMillis()
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > 60_000L) recent.removeFirst()
        val window = if (OSC_WINDOW_MS > 0) OSC_WINDOW_MS else 1L
        val inWindow = recent.count { now - it <= window }
        val needOsc = if (OSC_BURSTS < 1) 1 else OSC_BURSTS
        val next = when {
            worstMs >= SEIZURE_MS -> "SEIZURE"
            inWindow >= needOsc -> "OSCILLATION"
            else -> "HICCUP"
        }
        val changed = next != state
        state = next
        PerformanceTelemetryRegistry.publishStutter(state, recent.size.toFloat(), worstMs)
        // CRITICAL FIX PHASE3: publishStutter was NEVER called — stutterIsSevere was always false.
        // SpeedCompensationContributor, LoadShedGovernor all read this — all were blind to stutter.
        AdapterSignalBus.publishStutter(state)
        
        // MASSIVE POWER: Performance Bee Intervention
        val aggro = com.assistant.diagnostic.AdapterSignalBus.filterAggression
        if (next == "SEIZURE") {
            try { com.assistant.diagnostic.AdapterSignalBus.publishExecutionBrake(2) } catch (_: Throwable) {}
            if (aggro > 1.5f) {
                RuntimeLogger.log("STUTTER SEIZURE: Execution brake applied despite Filter Extremist Push (${"%.2f".format(aggro)}x)", "STUTTER_BEE")
            } else {
                RuntimeLogger.log("STUTTER SEIZURE: Execution brake applied to protect SmartAssist", "STUTTER_BEE")
            }
        } else if (next == "CALM") {
            try { com.assistant.diagnostic.AdapterSignalBus.publishExecutionBrake(0) } catch (_: Throwable) {}
        }
        if (changed) {
        }
        if (changed || now - lastLogMs >= 30_000L) {
            lastLogMs = now
            RuntimeLogger.log("burst " + state + " frames=" + frames +
                " worst=" + String.format("%.0f", worstMs) +
                "ms bursts60s=" + recent.size + " inWindow=" + inWindow, "STUTTER")
        }
    }

    /** decay back to CALM after an admin-tunable quiet time */
    fun startDecay() {
        if (decayRunning) return
        decayRunning = true
        val t = Thread {
            while (decayRunning) {
                try {
                    val nap = DECAY_POLL_MS
                    Thread.sleep(if (nap > 0) nap else 1L)
                    val now = System.currentTimeMillis()
                    val quiet = if (CALM_AFTER_MS > 0) CALM_AFTER_MS else 1L
                    synchronized(this) {
                        if (state != "CALM" &&
                            (recent.isEmpty() || now - recent.last() > quiet)) {
                            state = "CALM"
                            AdapterSignalBus.publishStutter("CALM")  // PHASE3 fix
                            PerformanceTelemetryRegistry.publishStutter("CALM", 0f, 0f)
                            RuntimeLogger.log("burst CALM restored", "STUTTER")
                        }
                    }
                } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "stutter-decay"; t.start()
    }

    fun stopDecay() { decayRunning = false }
}

/* ========
PanelWatchEngine
======== */
// V3 ADMIN-WIRED - NEW ENGINE: closes the adaptive-panel loophole

/**
 * Modern phone screens CHANGE their rhythm mid-game (60/90/120Hz adaptive
 * panels). The burst radar grades every frame against the screen's beat -
 * if the panel switches rhythm and nobody updates that beat, every reading
 * after the switch is graded against a lie: fake bursts on a faster panel,
 * missed real ones on a slower panel. Silent, invisible mis-detection.
 *
 * This engine closes that loophole for good: it registers the OS
 * display-change signal and pushes the new beat to the radar the INSTANT
 * the panel switches, with a belt-and-braces sweep as backup (rhythm
 * admin-tunable).
 */

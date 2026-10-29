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

object LagVerdictEngine {

    // OMEGA FIX: Zero-Delay Micro-Defect Elimination
    // 2000ms poll rate is 4 seconds to detect CHOKING. Too slow for eFootball.
    private val POLL_MS: Long get() = 500L
    private val JITTER_MS: Float get() = 8f
    private val STABILITY_PCT: Float get() = 75f
    private val CHOKE_STALLS: Float get() = 12f
    private val CHOKE_MTSTALL_MS: Float get() = 80f
    private val CHOKE_SPIKES: Float get() = 15f
    private val CONFIRM_POLLS: Int get() = 1 // Instant confirmation for severe spikes

    @Volatile private var running = false
    @Volatile var verdict = "UNKNOWN"; private set
    @Volatile private var candidate = "UNKNOWN"
    @Volatile private var streak = 0
    @Volatile private var lastHeartbeat = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val jit = FramePacingEngine.jitterMs
                    val stab = FramePacingEngine.stabilityPct
                    val stallRate = FramePacingEngine.stallsPerMin
                    val mtStall = MainThreadStallEngine.avgLatenessMs
                    val spm = MainThreadStallEngine.spikesPerMin
                    val raw = when {
                        stallRate > CHOKE_STALLS || mtStall > CHOKE_MTSTALL_MS ||
                            spm > CHOKE_SPIKES -> "CHOKING"
                        jit > JITTER_MS || stab < STABILITY_PCT -> "JITTERY"
                        FramePacingEngine.avgGapMs > 0f -> "SMOOTH"
                        else -> "UNKNOWN"
                    }
                    if (raw == candidate) streak++ else { candidate = raw; streak = 1 }
                    // Crowded zone (penalty box / corner) temporarily
                    // inflates stall/jitter from rendering load, not
                    // sustained device stress. Require 2 extra polls
                    // before confirming CHOKING to protect attacking actions.
                    val basePoll = if (CONFIRM_POLLS < 1) 1 else CONFIRM_POLLS
                    val need = if (raw == "CHOKING" && AdapterSignalBus.crowdingZone)
                        basePoll + 2 else basePoll
                    val now = System.currentTimeMillis()
                    if (candidate != verdict && streak >= need) {
                        RuntimeLogger.log("DEVICE " + verdict + " -> " + candidate +
                            " (jitter=" + String.format("%.1f", jit) +
                            "ms stability=" + String.format("%.0f", stab) +
                            "% stalls/min=" + String.format("%.1f", stallRate) +
                            " mtStall=" + String.format("%.0f", mtStall) +
                            "ms therm=" + ThermalPeekEngine.status + ")", "LAGVERDICT")
                        verdict = candidate
                        lastHeartbeat = now
                        
                        // MASSIVE POWER: Performance Bee Intervention
                        val aggro = com.assistant.diagnostic.AdapterSignalBus.filterAggression
                        if (candidate == "CHOKING") {
                            try { com.assistant.diagnostic.AdapterSignalBus.publishExecutionBrake(2) } catch (_: Throwable) {}
                            if (aggro > 1.5f) {
                                RuntimeLogger.log("LAG CHOKING: Execution brake applied despite Filter Extremist Push (${"%.2f".format(aggro)}x)", "LAG_BEE")
                            } else {
                                RuntimeLogger.log("LAG CHOKING: Execution brake applied to protect SmartAssist", "LAG_BEE")
                            }
                        } else if (candidate == "SMOOTH") {
                            try { com.assistant.diagnostic.AdapterSignalBus.publishExecutionBrake(0) } catch (_: Throwable) {}
                        }
                    } else if (now - lastHeartbeat >= 60_000L) {
                        lastHeartbeat = now
                        RuntimeLogger.log("DEVICE " + verdict +
                            " (jitter=" + String.format("%.1f", jit) +
                            "ms stability=" + String.format("%.0f", stab) +
                            "% therm=" + ThermalPeekEngine.status + ")", "LAGVERDICT")
                    }
                    AdapterSignalBus.publishLag(verdict)
                    PerformanceTelemetryRegistry.publishDisplay(
                        FramePacingEngine.avgGapMs, stallRate, mtStall, verdict)
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-verdict"; t.start()
    }

    fun stop() { running = false }
}

// --- MERGED: LoadShedCaptureBrakeEngine.kt ---

/**
 * LoadShedCaptureBrakeEngine — load shed → execution feedback bridge.
 *
 * PROVEN GAP: LoadShedGovernor.level is published to
 * PerformanceTelemetryRegistry and stops there. No component with
 * execution authority (OverlayService capture loop, gesture queue)
 * reads the load shed level to adjust behavior.
 *
 * This engine translates load shed level into an execution throttle
 * signal published to AdapterSignalBus, consumed by OverlayService's
 * capture gate and the gesture submission path.
 *
 *   NONE  → executionBrake = 0  (normal operation)
 *   LIGHT → executionBrake = 1  (reduce capture rate 15fps, keep gestures)
 *   HEAVY → executionBrake = 2  (10fps capture, suppress MOVE-class only gestures)
 *
 * The brake is polled every 500ms (faster than LoadShedGovernor's 2s poll)
 * so it responds within one poll cycle after the governor decides.
 */

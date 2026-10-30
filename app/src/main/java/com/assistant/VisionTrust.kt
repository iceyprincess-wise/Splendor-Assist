package com.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.WindowManager
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.GameplayEngineRegistry
import com.assistant.runtime.RuntimeFrame
import com.assistant.runtime.ZoneDistribution
import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.FileWriter
import java.lang.ref.WeakReference
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter

object VisionTrust {

    // ---------------- tunables (ADMIN-TUNABLE, defaults = original values) ----------------
    // PHASE4: 15fps tuning — FRESH_MS=120ms = 1.8 frames → ball always seems stale at 15fps
    // → frameTrusted() returns false every other frame → ALL contributors blocked
    // Fix: FRESH_MS=200ms (3 frames), STALE_MS=600ms (9 frames) for 15fps operation
    private val FRESH_MS: Long get() = 200L
    private val STALE_MS: Long get() = 600L
    private val LATENCY_LIMIT_MS: Float get() = 300f  // PHASE4B: 15fps main-thread spikes up to 250ms are normal
    private val TRUST_FLOOR: Float get() = 0.55f
    private val LANE_FLOOR: Float get() = 0.35f

    /** a real match cannot contain more than this many tracked entities */
    private val SANE_ENTITY_MAX: Int get() = 30

    // ---------------- gap 1c: is the game actually on screen ----------------
    @Volatile private var foregroundIsGame = false
    @Volatile private var lastForegroundPkg = ""
    @Volatile private var gatedFrames = 0L

    // SPLENDOR_FG_TRUTH_V1 - real eFootball package ids. onForegroundPackage()
    // matched against a caller-supplied set, but no caller ever existed, so the
    // set was never populated and the gate had no real input.
    @JvmField
    val GAME_PACKAGES: Set<String> = setOf("jp.konami.pesam")

    @JvmStatic
    fun onForegroundPackage(pkg: String?, gamePkgs: Set<String>) {
        val p = pkg ?: ""
        lastForegroundPkg = p
        if (p.isEmpty()) return  // FIX P3: eFootball 2027 child surface sends empty pkg
        // SPLENDOR_FG_TRUTH_V2
        // Every non-empty window-package event is authoritative.
        // Leaving eFootball immediately clears gameplay execution trust.
        foregroundIsGame = gamePkgs.any { p.contains(it, true) }
    }

    /** explicit override for callers that already resolved the decision */
    @JvmStatic
    fun setGameForeground(isGame: Boolean) { foregroundIsGame = isGame }

    @JvmStatic
    fun isGameForeground(): Boolean {
        // SPLENDOR_FG_TRUTH_V1 - read the REAL game-foreground flag.
        // Returning only !ownUiVisible() discarded foregroundIsGame entirely,
        // so setGameForeground(false) and onForegroundPackage() could never
        // block a gesture and GestureExecutionAuthority's final execution gate
        // was inert.
        if (com.assistant.vision.ForegroundGate.ownUiVisible()) return false
        // Cold start: before any window-state change has been observed there is
        // no package evidence, but an EXPLICIT setGameForeground() override from the
        // self-heal agent or ignition must be honored. Returning false here ignored
        // the override entirely and starved every gesture at cold start
        // (SPLENDOR_F33_FOREGROUND_LATCH, found by independent review).
        if (lastForegroundPkg.isEmpty()) return foregroundIsGame
        return foregroundIsGame
    }

    /** true when this frame must not be ingested at all */
    @JvmStatic
    fun shouldGateFrame(): Boolean {
        if (com.assistant.vision.ForegroundGate.ownUiVisible()) { gatedFrames++; return true }
        return false
    }

    // ---------------- gap 2: age-decayed ball trust ----------------
    @Volatile private var lastBallStampMs = 0L
    @Volatile private var lastBallConfidence = 0f
    @Volatile private var lastLatencyMs = 0f
    @Volatile private var insaneRejects = 0L

    @JvmStatic
    fun stampBall(confidence: Float, nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        lastBallStampMs = nowMs
        lastBallConfidence = confidence.coerceIn(0f, 1f)
    }

    @JvmStatic
    fun stampLatency(ms: Float) { lastLatencyMs = ms }

    /** confidence faded by how long ago we actually saw the ball */
    @JvmStatic
    @JvmOverloads
    fun ballTrust(nowMs: Long = android.os.SystemClock.elapsedRealtime()): Float {
        if (lastBallStampMs == 0L) return 0f
        val age = nowMs - lastBallStampMs
        val fresh = FRESH_MS
        val stale = STALE_MS
        val span = (stale - fresh).coerceAtLeast(1L)
        val decay = when {
            age <= fresh -> 1f
            age >= stale -> 0f
            else -> 1f - (age - fresh).toFloat() / span.toFloat()
        }
        return (lastBallConfidence * decay).coerceIn(0f, 1f)
    }

    /**
     * Entity-count sanity. A frame claiming 100 players is reading UI, not a pitch.
     * Returns false and counts a reject so the log shows how often it fires.
     */
    @JvmStatic
    fun entityCountSane(players: Int, opponents: Int): Boolean {
        val max = SANE_ENTITY_MAX
        if (players > max || opponents > max) {
            insaneRejects++
            return false
        }
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun frameTrusted(players: Int = 0, opponents: Int = 0): Boolean {
        if (!isGameForeground()) return false
        if (players > 0 || opponents > 0) {
            if (!entityCountSane(players, opponents)) return false
        }
        if (lastLatencyMs > LATENCY_LIMIT_MS) return false
        return (players > 0 || opponents > 0 || ballTrust() >= TRUST_FLOOR)
    }

    // ---------------- gap 3: motion stability ----------------
    private val vx = FloatArray(3)
    private val vy = FloatArray(3)
    @Volatile private var vIdx = 0
    @Volatile private var vCount = 0

    @JvmStatic
    fun pushMotion(dx: Float, dy: Float) {
        synchronized(vx) {
            vx[vIdx] = dx; vy[vIdx] = dy
            vIdx = (vIdx + 1) % 3
            if (vCount < 3) vCount++
        }
    }

    /** 0 = jittering noise, 1 = steady consistent travel */
    @JvmStatic
    fun directionStability(): Float {
        if (vCount < 3) return 0f
        var acc = 0f; var pairs = 0
        synchronized(vx) {
            for (i in 0 until 3) {
                val j = (i + 1) % 3
                val m1 = Math.sqrt((vx[i]*vx[i] + vy[i]*vy[i]).toDouble()).toFloat()
                val m2 = Math.sqrt((vx[j]*vx[j] + vy[j]*vy[j]).toDouble()).toFloat()
                if (m1 < 0.0001f || m2 < 0.0001f) continue
                val cos = ((vx[i]*vx[j] + vy[i]*vy[j]) / (m1 * m2)).coerceIn(-1f, 1f)
                acc += (cos + 1f) / 2f
                pairs++
            }
        }
        return if (pairs == 0) 0f else (acc / pairs).coerceIn(0f, 1f)
    }

    // ---------------- gap 3: lane confidence ----------------
    @Volatile private var laneSpread = 0f

    /** 0 = defenders packed (closed), 1 = spread wide (open) */
    @JvmStatic
    fun stampLaneSpread(spread: Float) { laneSpread = spread.coerceIn(0f, 1f) }

    @JvmStatic
    fun laneConfidence(): Float {
        val trust = ballTrust()
        if (trust < LANE_FLOOR) return 0f
        return (trust * 0.4f + laneSpread * 0.3f + directionStability() * 0.3f)
            .coerceIn(0f, 1f)
    }

    // ---------------- proof surface ----------------
    private val ticks = java.util.concurrent.atomic.AtomicLong(0L)

    @JvmStatic
    fun diagnostics(): String =
        "fg=" + foregroundIsGame + " pkg=" + lastForegroundPkg +
        " gated=" + gatedFrames +
        " ballTrust=" + String.format("%.3f", ballTrust()) +
        " lane=" + String.format("%.3f", laneConfidence()) +
        " stability=" + String.format("%.3f", directionStability()) +
        " spread=" + String.format("%.3f", laneSpread) +
        " latency=" + lastLatencyMs +
        " insaneRejects=" + insaneRejects

    @JvmStatic
    @JvmOverloads
    fun tickAndLog(every: Long = 20L) {
        if (ticks.incrementAndGet() % every != 0L) return
        try { com.assistant.diagnostic.RuntimeLogger.log(diagnostics(), "VISIONTRUST") } catch (_: Throwable) { }
    }
}


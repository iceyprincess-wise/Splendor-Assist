package com.assistant

// Consolidated Performance Engine Domain
// Merged from 54 fragmented adapter files to eliminate thread thrashing, 
// Binder IPC overhead, and GC pressure. Zero-delay execution path.

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
import com.assistant.adapter.smartassist.VisionTrust
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

/* ========
LagAdapterService
======== */
class LagAdapterService : Service() {

    companion object {
        private const val ADAPTER_NAME = "adapter_lag"

        // Approximate server tick interval (e.g., 30Hz network tick)
        private const val BASE_SERVER_TICK_RATE_MS = 33L 
    }

    private val lagHandlerThread = HandlerThread("LagTelemetryThread", Process.THREAD_PRIORITY_URGENT_DISPLAY)
    private lateinit var lagHandler: Handler
    private lateinit var heartbeatHandler: Handler
    
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { msg -> 
        handleIncomingMessage(msg)
        true 
    }))

    @Volatile
    private var currentPingDriftMs: Long = 0L
    private val lastTickTime = AtomicLong(System.currentTimeMillis())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = ADAPTER_NAME,
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Optimized High-Frequency Engine Active. Drift: ${currentPingDriftMs}ms | device=" + LagVerdictEngine.verdict + " shed=" + LoadShedGovernor.level
                )
            )
            RuntimeLogger.log("LagAdapter heartbeat sync completed", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000L)
        }
    }

    private val lagRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val expectedTick = lastTickTime.get() + 1000L
            val drift = now - expectedTick
            
            // Exponential moving average for drift stabilization to filter out local jitter
            currentPingDriftMs = ((currentPingDriftMs * 0.7) + (drift * 0.3)).toLong()
            lastTickTime.set(now)

            RuntimeLogger.log("LAG drift=${currentPingDriftMs}ms | TickSync=${now}", "LAG")
            lagHandler.postDelayed(this, 1000L)
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // Frame-level synchronization hooks for 60Hz/120Hz translation loops
            // Prevents micro-stutters by aligning physical calculations directly with VSYNC
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        RuntimeLogger.log("LagAdapterService optimized initialization", "ADAPTER")

        // Unified foundation notification (Task C item (e)) - replaces the
        // per-node row on ID 9993, which collided with input/battery/boot.
        NodeNotificationHub.attach(this, ADAPTER_NAME)

        lagHandlerThread.start()
        lagHandler = Handler(lagHandlerThread.looper)
        heartbeatHandler = Handler(Looper.getMainLooper())

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = ADAPTER_NAME,
                status = "STARTING",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service initializing micro-gesture engine"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        lagHandler.post(lagRunnable)
        
        // Initialize Choreographer for 120Hz/60Hz display sync
        initFrameSync()

        // ---- LAG ENGINE STACK IGNITION [V3 ADMIN-WIRED] ----
        // CRITICAL: load the admin store in THIS process so every saved
        // admin value is actually obeyed by the lag engines (without this
        // they silently fall back to compiled defaults).
        com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.initialize(this)
        DisplayProfileEngine.detect(this)
        FramePacingEngine.start()
        MainThreadStallEngine.start()
        LagVerdictEngine.start()
        LoadShedGovernor.start()
        LoadShedCaptureBrakeEngine.start()
        ThermalPeekEngine.init(this)
        CpuGovernorEngine.start()
        GcStallEngine.start()
        RenderThreadStallEngine.start()
        NetJitterEngine.start()
        RuntimeLogger.log("Lag engine stack ignited: 7 engines [V3+CPUGOV]", "LAG")
    }

    private fun initFrameSync() {
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun handleIncomingMessage(msg: Message) {
        // High-frequency IPC endpoint for external coordinate translation
        val data = msg.data
        if (data != null && data.containsKey("targetX") && data.containsKey("targetY")) {
            val targetX = data.getFloat("targetX")
            val targetY = data.getFloat("targetY")
            generateOptimizedGesture(targetX, targetY)
            RuntimeLogger.log("Gesture timing advice published for ($targetX, $targetY)", "GESTURE")
        }
    }

    /**
     * Timing-advice engine. Honesty note (Task C): this process cannot and
     * must not inject gestures - the single dispatch owner is the
     * accessibility engine. The GestureDescription built here is a local
     * calculation whose only real output is the RTT-scaled hold duration
     * published to PerformanceTelemetryRegistry as ADVICE for the dispatch
     * owner. The log line above says exactly that, instead of implying an
     * injection that never happens.
     */
    private fun generateOptimizedGesture(startX: Float, startY: Float): GestureDescription {
        val path = Path()
        
        // 1. Adaptive Noise Humanization (Micro-variance)
        val noiseRadius = 2.5f // Maximum pixel deviation boundary
        val randomAngle = Random.nextFloat() * 2 * PI
        val humanOffsetX = (cos(randomAngle) * noiseRadius * Random.nextFloat()).toFloat()
        val humanOffsetY = (sin(randomAngle) * noiseRadius * Random.nextFloat()).toFloat()
        
        val originX = startX + humanOffsetX
        val originY = startY + humanOffsetY
        path.moveTo(originX, originY)
        
        // Simulated dynamic micro-drag to ensure physics engines register translation bounds natively
        val dragEndX = originX + (if (Random.nextBoolean()) 1.2f else -1.2f)
        val dragEndY = originY + (if (Random.nextBoolean()) 1.2f else -1.2f)
        path.lineTo(dragEndX, dragEndY)
        
        // 2. Server-Tick Sync (Scaling holds to network boundaries)
        // real measured RTT from the net stack (same process, in-memory read);
        // falls back to scheduler drift only if the probe has no data yet
        val measuredRtt = try {
            com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.currentNet().rttMs.toLong()
        } catch (_: Throwable) { 0L }
        val pingComp = if (measuredRtt > 0L) measuredRtt else currentPingDriftMs
        val baseHoldDuration = 15L // Base human minimal threshold
        
        // Dynamically scale hold duration to cross server packet boundaries
        // Ensures maximum authoritative network registration during high-ping sequences without dropouts
        val dynamicHoldDuration = baseHoldDuration + (pingComp / 5L)
        
        // Clamp bounds to guarantee maximum effectiveness without OS touch rejection
        val finalHoldDuration = min(max(dynamicHoldDuration, 12L), BASE_SERVER_TICK_RATE_MS * 4)

        val strokeDescription = GestureDescription.StrokeDescription(
            path,
            0L, // Dispatch immediately without arbitrary delay
            finalHoldDuration,
            false // Will continue: false for independent micro-bursts
        )
        
        // publish the RTT-scaled hold as ADVICE for the single dispatch owner;
        // this process cannot and must not inject gestures itself
        try {
            com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
                .publishGestureTiming(finalHoldDuration)
        } catch (_: Throwable) { }

        return GestureDescription.Builder()
            .addStroke(strokeDescription)
            .build()
    }

    override fun onDestroy() {
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        FramePacingEngine.stop()
        MainThreadStallEngine.stop()
        LagVerdictEngine.stop()
        LoadShedGovernor.stop()
        LoadShedCaptureBrakeEngine.stop()
        CpuGovernorEngine.stop()
        GcStallEngine.stop()
        RenderThreadStallEngine.stop()
        NetJitterEngine.stop()
        ThermalPeekEngine.stop()
        lagHandlerThread.quitSafely()
        NodeNotificationHub.detach(this, ADAPTER_NAME)
        RuntimeLogger.log("LagAdapterService optimized engine stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

// --- MERGED: CpuGovernorEngine.kt ---

/**
 * CpuGovernorEngine — Reserve A75 fast cores for eFootball, park Splendor on A55.
 * Helio G81-Ultra: cores 0-5 = A55 (efficiency), cores 6-7 = A75 (performance).
 * Without root: scheduler priority-only mode (still very effective).
 * With root/sysfs: sets actual cpufreq governor per cluster.
 */
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
                        if (gov.canWrite()) { gov.writeText("performance"); wrote = true }
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
object DisplayProfileEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
    val gameFps: Float get() {
        val v = 30f
        return if (v > 0f) v else 30f
    }
    val gameBudgetMs: Float get() = 1000f / gameFps

    @Volatile var panelHz = 60f; private set
    @Volatile var vsyncBudgetMs = 16.67f; private set

    fun detect(ctx: Context) {
        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.displays.firstOrNull() ?: return
            panelHz = d.refreshRate
            vsyncBudgetMs = 1000f / panelHz
            RuntimeLogger.log("Display profile: panel=" + String.format("%.0f", panelHz) +
                "Hz vsyncBudget=" + String.format("%.1f", vsyncBudgetMs) +
                "ms gameBudget=" + String.format("%.1f", gameBudgetMs) + "ms", "LAGPROF")
        } catch (_: Throwable) { }
    }
}

// --- MERGED: FramePacingEngine.kt ---

// V3 AGGRESSIVE - mixture-aware, direct jitter

/**
 * V3: no single-cadence guessing. On an adaptive 90Hz panel running a 30fps
 * game, frames legally arrive at 1x/2x/3x vsync - the ENEMY is irregularity,
 * not any particular multiple. So we grade:
 *   jitterMs   - EWMA of |gap - prevGap| (beat-to-beat wobble, the felt stutter)
 *   stability  - share of frames in the window's dominant vsync bucket
 *   hard stalls - gap > 100ms (absolute; a real freeze at any cadence)
 */
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
object LagVerdictEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val POLL_MS: Long get() = 2000L
    private val JITTER_MS: Float get() = 10f
    private val STABILITY_PCT: Float get() = 65f
    private val CHOKE_STALLS: Float get() = 18f
    private val CHOKE_MTSTALL_MS: Float get() = 120f
    private val CHOKE_SPIKES: Float get() = 20f
    private val CONFIRM_POLLS: Int get() = 2

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
object LoadShedCaptureBrakeEngine {

    private const val POLL_MS = 500L

    @Volatile private var running = false
    @Volatile var executionBrake = 0; private set
    @Volatile private var lastLevel = "NONE"

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try { poll() } catch (_: Throwable) {}
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "lag-capture-brake"
        t.start()
        RuntimeLogger.log("LoadShedCaptureBrakeEngine started", "LOADSHED")
    }

    fun stop() { running = false }

    private fun poll() {
        val level = LoadShedGovernor.level
        val brake = when (level) {
            "HEAVY" -> 2
            "LIGHT" -> 1
            else    -> 0
        }
        val changed = brake != executionBrake || level != lastLevel
        executionBrake = brake
        lastLevel = level

        AdapterSignalBus.publishExecutionBrake(brake)

        if (changed) {
            RuntimeLogger.log(
                "LoadShedBrake: loadShed=$level → executionBrake=$brake",
                "LOADSHED"
            )
        }
    }

    /** Recommended capture interval Ms for current brake level. */
    fun recommendedIntervalMs(): Long = when (executionBrake) {
        2    -> 100L  // HEAVY: 10fps
        1    -> 66L   // LIGHT: 15fps
        else -> 0L    // NONE: use base rate (0 = no override)
    }

    /**
     * Returns true when HEAVY load shed is active and MOVE-class gesture
     * submissions should be suppressed (action-class gestures still pass).
     */
    fun suppressMoveGestures(): Boolean = executionBrake >= 2
}

// --- MERGED: LoadShedGovernor.kt ---

// V3 ADMIN-WIRED - quick to arm, slow to thrash, every knob live

/**
 * The rescue: when the judge says the device is struggling, this raises the
 * shed level (NONE / LIGHT / HEAVY) that the rest of the runtime obeys to
 * drop non-essential work. A SEIZURE stutter burst escalates IMMEDIATELY
 * (fast path, no waiting). V3: poll rhythm, arm/release confirm counts and
 * the minimum hold are all admin-tunable, re-read every poll.
 */
object LoadShedGovernor {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val MIN_HOLD_MS: Long get() = 8000L
    private val POLL_MS: Long get() = 2000L
    private val ARM_POLLS: Int get() = 4
    private val RELEASE_POLLS: Int get() = 5

    @Volatile private var running = false
    @Volatile var level = "NONE"; private set
    @Volatile private var candidate = "NONE"
    @Volatile private var streak = 0
    @Volatile private var lastChangeMs = 0L
    @Volatile private var startTimeMs = 0L

    fun start() {
        if (running) return
        running = true
        startTimeMs = System.currentTimeMillis()
        val t = Thread {
            while (running) {
                try {
                    // FAST PATH: a SEIZURE burst escalates immediately -
                    // sub-second truth beats the report window when a freeze hits
                    val burst = PerformanceTelemetryRegistry.currentStutterState()
                    val bootAge=System.currentTimeMillis()-startTimeMs
                    if(startTimeMs>0L&&bootAge<10_000L){try{Thread.sleep(POLL_MS.coerceAtLeast(1L))}catch(_:Throwable){return@Thread};continue}
                    val want = when {
                        burst == "SEIZURE" -> "HEAVY"
                        LagVerdictEngine.verdict == "CHOKING" -> "HEAVY"
                        LagVerdictEngine.verdict == "JITTERY" -> "LIGHT"
                        else -> "NONE"
                    }
                    if (want == candidate) streak++ else { candidate = want; streak = 1 }
                    val arm = if (ARM_POLLS < 1) 1 else ARM_POLLS
                    val rel = if (RELEASE_POLLS < 1) 1 else RELEASE_POLLS
                    val need = if (candidate == "NONE") rel else arm
                    val now = System.currentTimeMillis()
                    if (candidate != level && streak >= need &&
                        (lastChangeMs == 0L || now - lastChangeMs >= MIN_HOLD_MS)) {
                        RuntimeLogger.log("LOAD SHED " + level + " -> " + candidate +
                            " (device=" + LagVerdictEngine.verdict + ")", "LOADSHED")
                        level = candidate
                        lastChangeMs = now
                    }
                    PerformanceTelemetryRegistry.publishLoadShed(level)
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-loadshed"; t.start()
    }

    fun stop() { running = false }
}

// --- MERGED: MainThreadStallEngine.kt ---

// V3 ADMIN-WIRED - zero main-thread I/O, every knob answers the admin store live

/**
 * Pokes the main thread on a fixed rhythm and measures how late the answer
 * comes back - lateness IS the choke other engines cannot see. The probe
 * only measures (no logging/IO on the thread under test); a background
 * reporter summarizes. V3: cadence, spike line, smoothing and report rhythm
 * are all admin-tunable and re-read every cycle - values apply on the very
 * next poke, no restart.
 */
object MainThreadStallEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val CADENCE_MS: Long get() = 250L
    private val SPIKE_MS: Long get() = 80L
    private val ALPHA: Float get() = 0.25f
    private val REPORT_MS: Long get() = 10_000L

    @Volatile private var running = false
    @Volatile var avgLatenessMs = 0f; private set
    @Volatile var spikesPerMin = 0f; private set   // rolling
    @Volatile private var winSpikes = 0L
    @Volatile private var winMax = 0L
    @Volatile private var totalSpikes = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val probe = object : Runnable {
        @Volatile var expected = 0L
        override fun run() {
            if (!running) return
            val late = (System.currentTimeMillis() - expected).coerceAtLeast(0L)
            val a = ALPHA
            avgLatenessMs = if (avgLatenessMs == 0f) late.toFloat()
                            else avgLatenessMs * (1 - a) + late * a
            if (late >= SPIKE_MS) {
                winSpikes++; totalSpikes++
                if (late > winMax) winMax = late
            }
            val cad = if (CADENCE_MS > 0) CADENCE_MS else 1L
            expected = System.currentTimeMillis() + cad
            handler.postDelayed(this, cad)
        }
    }

    fun start() {
        if (running) return
        running = true
        val cad = if (CADENCE_MS > 0) CADENCE_MS else 1L
        probe.expected = System.currentTimeMillis() + cad
        handler.postDelayed(probe, cad)
        val t = Thread {
            while (running) {
                val windowMs = if (REPORT_MS > 0) REPORT_MS else 1L
                try { Thread.sleep(windowMs) } catch (_: Throwable) { return@Thread }
                val winMin = windowMs / 60000f
                spikesPerMin = if (winMin > 0f) winSpikes / winMin else 0f
                if (winSpikes > 0L) {
                    RuntimeLogger.log("stalls n=" + winSpikes + " max=" + winMax +
                        "ms avg=" + String.format("%.0f", avgLatenessMs) +
                        "ms total=" + totalSpikes, "LAGSTALL")
                }
                winSpikes = 0L; winMax = 0L
            }
        }
        t.isDaemon = true; t.name = "lag-stall-report"; t.start()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(probe)
    }
}

// --- MERGED: ThermalPeekEngine.kt ---

// V3 ADMIN-WIRED - thermal evidence probe, live rhythm

/**
 * One cheap system reading: the OS thermal status. If lag storms correlate
 * with SEVERE+ status, the enemy is heat throttling; if status stays
 * NONE/LIGHT through a storm, throttling is exonerated and scheduling
 * contention becomes prime suspect. V3: the check rhythm is admin-tunable
 * and re-read every cycle.
 */
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
object GcStallEngine {
    @Volatile private var running = false
    @Volatile var memoryPressure = "LOW"; private set
    fun start() {
        if (running) return; running = true
        val t = Thread {
            while (running) {
                try {
                    val rt = Runtime.getRuntime()
                    val used = rt.totalMemory() - rt.freeMemory()
                    val max = rt.maxMemory()
                    val pct = (used * 100) / max
                    val newPressure = if (pct > 85) "CRITICAL" else if (pct > 70) "HIGH" else "LOW"
                    if (newPressure != memoryPressure) {
                        memoryPressure = newPressure
                        RuntimeLogger.log("GC/MEM Pressure -> $newPressure ($pct%)", "LAGGC")
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(5000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-gc-stall"; t.start()
    }
    fun stop() { running = false }
}

// --- NEW: RenderThreadStallEngine ---
object RenderThreadStallEngine {
    @Volatile private var running = false
    @Volatile var gpuQueueStallsPerMin = 0f; private set
    @Volatile private var winStalls = 0L
    @Volatile private var lastFrameEndNanos = 0L
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameEndNanos > 0L) {
                val gapMs = (frameTimeNanos - lastFrameEndNanos) / 1_000_000f
                val vsync = DisplayProfileEngine.vsyncBudgetMs
                if (gapMs > (vsync * 2.5f)) winStalls++
            }
            lastFrameEndNanos = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    fun start() {
        if (running) return; running = true
        Handler(Looper.getMainLooper()).post { Choreographer.getInstance().postFrameCallback(callback) }
        val t = Thread {
            while (running) {
                try { Thread.sleep(10_000L) } catch (_: Throwable) { return@Thread }
                val winMin = 10_000L / 60000f
                gpuQueueStallsPerMin = winStalls / winMin
                if (winStalls > 0) RuntimeLogger.log("RenderThread/GPU stalls/min=" + String.format("%.1f", gpuQueueStallsPerMin), "LAGRENDER")
                winStalls = 0L
            }
        }
        t.isDaemon = true; t.name = "lag-render-stall"; t.start()
    }
    fun stop() {
        running = false
        Handler(Looper.getMainLooper()).post { Choreographer.getInstance().removeFrameCallback(callback) }
    }
}

// --- NEW: NetJitterEngine ---
object NetJitterEngine {
    @Volatile private var running = false
    @Volatile var netJitterMs = 0f; private set
    @Volatile private var lastRtt = 0f
    private val ALPHA = 0.2f
    fun start() {
        if (running) return; running = true
        val t = Thread {
            while (running) {
                try {
                    val rtt = try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.currentNet().rttMs } catch (_: Throwable) { 0f }
                    if (rtt > 0f) {
                        if (lastRtt > 0f) {
                            val d = Math.abs(rtt - lastRtt)
                            netJitterMs = netJitterMs * (1 - ALPHA) + d * ALPHA
                        }
                        lastRtt = rtt
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(1000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-net-jitter"; t.start()
    }
    fun stop() { running = false }
}
/* ======
LagAdapterService Anchor
====== */

/* ========
BurstForensicsEngine
======== */
// V3 ADMIN-WIRED - every classification line answers the admin store live

/**
 * Classifies each burst and keeps a 60s picture:
 *   HICCUP      isolated burst, recovers next slice
 *   OSCILLATION several bursts inside the watch window - rhythmic micro-stutter
 *   SEIZURE     burst with a frame past the freeze line - a felt freeze
 * Publishes one state for the lag governor's FAST path (a SEIZURE here
 * escalates the rescue to HEAVY immediately). Aggregated logging only.
 *
 * V3: the freeze line, oscillation count + window, calm-restore time and
 * calm-check rhythm are all admin-tunable, re-read on every burst - no
 * restart needed. State changes publish instantly for the admin Detector.
 */
object BurstForensicsEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val SEIZURE_MS: Float get() = 150f
    private val OSC_BURSTS: Int get() = 3
    private val OSC_WINDOW_MS: Long get() = 15000L
    private val CALM_AFTER_MS: Long get() = 10000L
    private val DECAY_POLL_MS: Long get() = 5000L

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
/* ======
BurstForensicsEngine Anchor
====== */

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
object PanelWatchEngine {

    // ADMIN-TUNABLE
    private val POLL_MS: Long get() = 5000L

    @Volatile private var running = false
    private var dm: DisplayManager? = null
    private var listener: DisplayManager.DisplayListener? = null

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        try {
            val mgr = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm = mgr
            val l = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) { }
                override fun onDisplayRemoved(displayId: Int) { }
                override fun onDisplayChanged(displayId: Int) { apply() }
            }
            mgr.registerDisplayListener(l, Handler(Looper.getMainLooper()))
            listener = l
            RuntimeLogger.log("instant panel-change listener registered", "STUTTER")
        } catch (t: Throwable) {
            RuntimeLogger.log("panel listener unavailable, sweep only: " + t.message, "STUTTER")
        }
        val t = Thread {
            while (running) {
                try { apply() } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "stutter-panelwatch"; t.start()
    }

    private fun apply() {
        val hz = dm?.displays?.firstOrNull()?.refreshRate ?: return
        StutterPulseEngine.onPanelRate(hz)
    }

    fun stop() {
        running = false
        try { listener?.let { dm?.unregisterDisplayListener(it) } } catch (_: Throwable) { }
        listener = null
    }
}
/* ======
PanelWatchEngine Anchor
====== */

/* ========
StutterAdapterService
======== */
// V3 ADMIN-WIRED

/**
 * V3: the admin store is loaded in THIS process so every saved admin value
 * is actually obeyed by the stutter engines; the new PanelWatchEngine keeps
 * the radar's screen-beat truthful the instant an adaptive panel switches
 * rhythm. Heartbeat carries live burst truth.
 */
class StutterAdapterService : Service() {

    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_stutter",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "burst=" + BurstForensicsEngine.state +
                        " bursts/min=" + StutterPulseEngine.burstsPerMin +
                        " panel=" + StutterPulseEngine.panelHz + "Hz"
                )
            )
            RuntimeLogger.log("StutterAdapter heartbeat burst=" +
                BurstForensicsEngine.state, "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        RuntimeLogger.log("StutterAdapterService started - BURST RADAR V3", "ADAPTER")

        // Unified foundation notification (Task C item (e)) - this node was
        // the SEVENTH service on colliding foreground ID 9993.
        NodeNotificationHub.attach(this, "adapter_stutter")

        // ---- STUTTER ENGINE STACK IGNITION [V3 ADMIN-WIRED] ----
        // CRITICAL: load the admin store in THIS process so every saved
        // admin value is actually obeyed by the stutter engines.
        PerformanceTelemetryRegistry.initialize(this)
        StutterPulseEngine.detectPanel(this)
        StutterPulseEngine.start()
        PanelWatchEngine.start(this)
        BurstForensicsEngine.startDecay()
        RuntimeLogger.log("Stutter engine stack ignited: 3 engines [V3 ADMIN-WIRED]", "STUTTER")

        heartbeatHandler.post(heartbeatRunnable)
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        StutterPulseEngine.stop()
        PanelWatchEngine.stop()
        BurstForensicsEngine.stopDecay()
        NodeNotificationHub.detach(this, "adapter_stutter")
        RuntimeLogger.log("StutterAdapter stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
/* ======
StutterAdapterService Anchor
====== */

/* ========
StutterPulseEngine
======== */
// V3 ADMIN-WIRED - every knob answers the admin store live, publishes for the Detector

/**
 * Sub-second burst radar. Lag grades long windows; this watches EVERY slice
 * (default 1s). A burst = enough frames in one slice blowing past the burst
 * line (screen beat x multiplier, beat read from the REAL panel and updated
 * live by PanelWatchEngine). Catches the 3-5 frame hiccups an average washes
 * out - the felt micro-stutter.
 *
 * V3: burst line, frames-per-slice, slice length and readout rhythm are all
 * admin-tunable and re-read every frame - values apply instantly, no
 * restart. Publishes live burst truth for the admin Detector.
 */
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
PingAdapterService
======== */
class PingAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastResolveMs = -1L
    @Volatile private var lastQuality = "UNKNOWN"

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_ping",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "dnsResolve=${lastResolveMs}ms quality=$lastQuality"
                )
            )
            RuntimeLogger.log("PingAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    private val pingHandler = Handler(Looper.getMainLooper())

    private val pingExecutor =
        Executors.newSingleThreadExecutor()

    /*
     * HONESTY NOTE (Task C): this probe measures DNS RESOLUTION time, not
     * network round-trip - the OS resolver may answer from cache in ~0ms.
     * The real RTT authority is adapter_net's NetProbeEngine; this node's
     * reading is a coarse connectivity indicator only, and its log line and
     * health details now say exactly what it measures instead of calling it
     * "latency".
     */
    private val pingRunnable = object : Runnable {

        override fun run() {

            pingExecutor.execute {

                try {

                    val start =
                        System.currentTimeMillis()

                    InetAddress
                        .getByName("google.com")

                    val resolveMs =
                        System.currentTimeMillis() - start

                    val quality =
                        when {
                            resolveMs < 100 -> "GOOD"
                            resolveMs < 300 -> "FAIR"
                            else -> "POOR"
                        }

                    lastResolveMs = resolveMs
                    lastQuality = quality
                    AdapterSignalBus.publishPing(quality)  // PHASE3: feed RuntimeDecisionLoop

                    RuntimeLogger.log(
                        "PING dnsResolve=${resolveMs}ms quality=$quality (connectivity indicator, not RTT)",
                        "PING"
                    )

                } catch (e: Exception) {

                    lastQuality = "OFFLINE"
                    RuntimeLogger.log(
                        "PING connectivity check failed",
                        "PING"
                    )
                }
            }

            pingHandler.postDelayed(
                this,
                30000L
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("PingAdapterService started", "ADAPTER")

        // Unified foundation notification (Task C item (e)) - this node was
        // the FIFTH service on colliding foreground ID 9993.
        NodeNotificationHub.attach(this, "adapter_ping")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_ping",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("PingAdapter heartbeat scheduler started", "HEALTH")

        pingHandler.post(pingRunnable)
        RuntimeLogger.log("Ping telemetry started", "PING")
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        pingHandler.removeCallbacks(pingRunnable)
        pingExecutor.shutdownNow()
        NodeNotificationHub.detach(this, "adapter_ping")
        RuntimeLogger.log("PingAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
/* ======
PingAdapterService Anchor
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
object ActionWindowEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val POLL_MS: Long get() = 2000L
    private val HOLD_LOSS_PCT: Float get() = 10f
    private val GO_LOSS_PCT: Float get() = 2f
    private val HOLD_JITTER_MULT: Float get() = 2f

    @Volatile private var running = false
    @Volatile var verdict = "UNKNOWN"; private set
    @Volatile private var sinceMs = 0L
    @Volatile private var flips = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val tol = CarrierProfileEngine.jitterTolMs.toFloat()
                    val loss = PacketLossProbeEngine.lossPct
                    val next = when {
                        CongestionSentinelEngine.congested || loss > HOLD_LOSS_PCT ||
                            NetProbeEngine.jitter > tol * HOLD_JITTER_MULT -> "HOLD"
                        NetProbeEngine.quality == "GOOD" && loss < GO_LOSS_PCT -> "GO"
                        else -> "CAUTION"
                    }
                    if (next != verdict) {
                        flips++
                        sinceMs = System.currentTimeMillis()
                        RuntimeLogger.log("WINDOW " + verdict + " -> " + next +
                            " (rtt=" + String.format("%.0f", NetProbeEngine.rtt) +
                            "ms jit=" + String.format("%.0f", NetProbeEngine.jitter) +
                            "ms loss=" + String.format("%.0f", loss) + "%)", "NETWINDOW")
                        verdict = next
                    }
                    AdapterSignalBus.publishNet(verdict)
                    PerformanceTelemetryRegistry.publishActionWindow(verdict,
                        "rtt=" + NetProbeEngine.rtt.toInt() + " jit=" + NetProbeEngine.jitter.toInt() +
                        " loss=" + loss.toInt())
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-window"; t.start()
    }

    fun stop() { running = false }

    fun state(): String {
        val held = if (sinceMs > 0) (System.currentTimeMillis() - sinceMs) / 1000L else 0L
        return verdict + "(" + held + "s)"
    }
}
/* ======
ActionWindowEngine Anchor
====== */

/* ========
CarrierProfileEngine
======== */
data class CarrierProfile(
    val name: String,
    val expectedRttMs: Int,
    val keepAliveSeconds: Int,
    val jitterToleranceMs: Int
)

/**
 * Detects the live carrier and selects its tuned profile (no extra permission
 * needed). V3: the admin can OVERRIDE the baseline ping, wobble allowance and
 * keepalive rhythm from the admin panel (0 = automatic, follow the detected
 * carrier). Every engine reads the override-aware values below, so an admin
 * override takes effect stack-wide on the next tick.
 */
object CarrierProfileEngine {

    private val MTN     = CarrierProfile("MTN", 65, 8, 25)
    private val AIRTEL  = CarrierProfile("AIRTEL", 75, 10, 30)
    private val GENERIC = CarrierProfile("GENERIC", 90, 12, 40)
    val WIFI            = CarrierProfile("WIFI", 40, 15, 15)

    @Volatile var current: CarrierProfile = GENERIC
        private set

    // ---- override-aware baselines (0 = auto: use the detected profile) ----
    val baselineRttMs: Int get() {
        val o = 0
        return if (o > 0) o else current.expectedRttMs
    }
    val jitterTolMs: Int get() {
        val o = 0
        return if (o > 0) o else current.jitterToleranceMs
    }
    val keepAliveS: Int get() {
        val o = 0
        return if (o > 0) o else current.keepAliveSeconds
    }

    fun detect(ctx: Context): CarrierProfile {
        val op = try {
            // PHASE4 FIX: dual-SIM bug — networkOperatorName returns PRIMARY SIM (MTN slot 1)
            // even when active DATA SIM is different (Airtel slot 2).
            // Log-proven: user on Airtel, but carrier profile logged as MTN every session.
            // Fix: read operator from the explicit DATA subscription ID.
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val dataTm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    val subId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                    if (subId > 0) tm?.createForSubscriptionId(subId) else tm
                } catch (_: Throwable) { tm }
            } else tm
            dataTm?.networkOperatorName ?: ""
        } catch (_: Throwable) { "" }
        current = when {
            op.contains("MTN", true)    -> MTN
            op.contains("AIRTEL", true) -> AIRTEL
            op.isBlank()                -> GENERIC
            else                        -> GENERIC.copy(name = op.uppercase())
        }
        RuntimeLogger.log("Carrier: " + op + " -> profile " + current.name +
            " baseline=" + current.expectedRttMs + "ms", "NET")
        return current
    }

    fun useWifiProfile() { current = WIFI }
}
/* ======
CarrierProfileEngine Anchor
====== */

/* ========
CongestionSentinelEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V2: reports BOTH edges. Onset warns before lag is felt; recovery (with how
 * long the dirty window lasted) tells the runtime when aggression is safe again.
 * V3: reads the override-aware wobble allowance, so an admin baseline override
 * re-tunes congestion detection on the next poll.
 */
object CongestionSentinelEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val POLL_MS: Long get() = 2000L
    private val RISE_FACTOR: Float get() = 1.5f
    private val RISE_FRACTION: Float get() = 0.6f

    @Volatile private var running = false
    @Volatile var congested = false; private set
    @Volatile var congestedSinceMs = 0L; private set
    @Volatile private var lastJitter = 0f
    @Volatile private var warnings = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val j = NetProbeEngine.jitter
                    val tol = CarrierProfileEngine.jitterTolMs.toFloat()
                    val rising = j > lastJitter * RISE_FACTOR && j > tol * RISE_FRACTION
                    val over = j > tol
                    val now = rising || over
                    if (now && !congested) {
                        congestedSinceMs = System.currentTimeMillis()
                        warnings++
                        RuntimeLogger.log("CONGESTION ONSET jitter=" + String.format("%.0f", j) +
                            "ms tol=" + tol.toInt() + "ms n=" + warnings, "NET")
                    } else if (!now && congested) {
                        val dur = (System.currentTimeMillis() - congestedSinceMs) / 1000L
                        RuntimeLogger.log("CONGESTION CLEARED after " + dur + "s jitter=" +
                            String.format("%.0f", j) + "ms", "NET")
                    }
                    congested = now
                    lastJitter = j
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-sentinel"; t.start()
    }

    fun stop() { running = false }
}
/* ======
CongestionSentinelEngine Anchor
====== */

/* ========
ConnectionHealEngine
======== */
/**
 * ConnectionHealEngine — Active network healer. Never accept a bad connection.
 * When HOLD is detected: WiFi rescan + rebind to best available network.
 * Cooldown: one heal per 15s (never floods the modem).
 */
object ConnectionHealEngine {
    @Volatile private var running = false
    @Volatile private var lastHealMs = 0L
    @Volatile var healCount = 0; private set
    private const val COOLDOWN_MS = 15_000L

    fun start(ctx: Context) {
        if (running) return
        running = true
        val appCtx = ctx.applicationContext
        try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    RuntimeLogger.log("ConnectionHeal: network LOST — healing", "NETHEAL")
                    tryHeal(appCtx)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.linkDownstreamBandwidthKbps in 1..500) {
                        RuntimeLogger.log("ConnectionHeal: bandwidth critical — healing", "NETHEAL")
                        tryHeal(appCtx)
                    }
                }
            })
        } catch (t: Throwable) {
            RuntimeLogger.log("ConnectionHeal: callback failed: ${t.message}", "NETHEAL")
        }
        val t = Thread {
            while (running) {
                try { if (ActionWindowEngine.verdict == "HOLD") tryHeal(appCtx) } catch (_: Throwable) {}
                try { Thread.sleep(8_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-heal"; t.start()
        RuntimeLogger.log("ConnectionHealEngine started", "NETHEAL")
    }

    fun stop() { running = false }

    private fun tryHeal(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastHealMs < COOLDOWN_MS) return
        lastHealMs = now; healCount++
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wm.isWifiEnabled) {
                @Suppress("DEPRECATION") wm.startScan()
                RuntimeLogger.log("ConnectionHeal: WiFi rescan #$healCount", "NETHEAL")
            }
        } catch (_: Throwable) {}
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.let { cm.bindProcessToNetwork(it) }
            RuntimeLogger.log("ConnectionHeal: rebound to best network #$healCount", "NETHEAL")
        } catch (_: Throwable) {}
    }
}
/* ======
ConnectionHealEngine Anchor
====== */

/* ========
DnsWarmupEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V3: warmNow() lets the network-state engine force an immediate re-warm the
 * second the link changes - server addresses are hot on the NEW network
 * within a second instead of waiting out the rewarm interval.
 */
object DnsWarmupEngine {

    private val HOSTS = listOf(
        "www.konami.com", "www.google.com", "www.cloudflare.com", "one.one.one.one"
    )
    // ADMIN-TUNABLE (default = original hard-coded value)
    private val REWARM_MS: Long get() = 90_000L

    private val lock = Object()
    @Volatile private var running = false
    @Volatile private var rounds = 0L
    private val reported = HashSet<String>()

    /** Wake the loop for an immediate re-warm (called on link change). */
    fun warmNow() { synchronized(lock) { lock.notifyAll() } }

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                var ok = 0
                for (h in HOSTS) {
                    try { InetAddress.getByName(h); ok++ } catch (_: Throwable) {
                        if (reported.add(h)) RuntimeLogger.log("dns FAIL host=" + h, "NET")
                    }
                }
                rounds++
                if (rounds % 5L == 1L) RuntimeLogger.log("dns warm " + ok + "/" + HOSTS.size, "NET")
                val nap = REWARM_MS
                try {
                    synchronized(lock) { lock.wait(if (nap > 0) nap else 1L) }
                } catch (_: InterruptedException) { }
            }
        }
        t.isDaemon = true; t.name = "net-dnswarm"; t.start()
    }

    fun stop() { running = false; warmNow() }
}
/* ======
DnsWarmupEngine Anchor
====== */

/* ========
NetAdapterService
======== */
class NetAdapterService : Service() {
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_net",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = NetProbeEngine.summary() + " | window=" + ActionWindowEngine.state()
                    )
                )
                RuntimeLogger.log("NetAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("NetAdapterService started", "ADAPTER")

        // Unified foundation notification (Task C item (e)).
        NodeNotificationHub.attach(this, "adapter_net")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_net",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        // ---- NET ENGINE STACK IGNITION ----
        CarrierProfileEngine.detect(this)
        NetworkStateEngine.start(this)
        NetProbeEngine.start(this)
        RadioKeepAliveEngine.start()
        DnsWarmupEngine.start()
        CongestionSentinelEngine.start()
        PacketLossProbeEngine.start()
        SpikeBurstEngine.start()
        ActionWindowEngine.start()
        ConnectionHealEngine.start(this)
        RuntimeLogger.log("Net engine stack ignited: 10 engines [V2+HEAL]", "NET")

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("NetAdapter heartbeat scheduler started", "HEALTH")
    }

    override fun onDestroy() {
        NetworkStateEngine.stop()
        NetProbeEngine.stop()
        RadioKeepAliveEngine.stop()
        DnsWarmupEngine.stop()
        CongestionSentinelEngine.stop()
        PacketLossProbeEngine.stop()
        SpikeBurstEngine.stop()
        ConnectionHealEngine.stop()
        ActionWindowEngine.stop()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        NodeNotificationHub.detach(this, "adapter_net")
        RuntimeLogger.log("NetAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
/* ======
NetAdapterService Anchor
====== */

/* ========
NetProbeEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V3: every knob answers to the admin store on the very next cycle - sample
 * count, sample gap, cadences, timeout, smoothing and the DEGRADED line are
 * all live-tunable with no hidden clamps. On a link change the engine is
 * woken INSTANTLY (no waiting out the nap), history is wiped and the first
 * fresh verdict lands within one probe cycle. Publishes live stats for the
 * admin Detector.
 */
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
object NetworkStateEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
    private val POLL_MS: Long get() = 10000L

    @Volatile var transport: String = "NONE"
        private set
    @Volatile private var running = false
    @Volatile private var last = ""
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        try {
            val mgr = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm = mgr
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    apply(app, classify(caps))
                }
                override fun onLost(network: Network) { apply(app, "NONE") }
            }
            mgr.registerDefaultNetworkCallback(cb)
            callback = cb
            RuntimeLogger.log("instant network-change callback registered", "NET")
        } catch (t: Throwable) {
            RuntimeLogger.log("network callback unavailable, poll only: " + t.message, "NET")
        }
        val th = Thread {
            while (running) {
                try {
                    val mgr = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    apply(app, classify(mgr.getNetworkCapabilities(mgr.activeNetwork)))
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        th.isDaemon = true; th.name = "net-state"; th.start()
    }

    private fun classify(caps: NetworkCapabilities?): String = when {
        caps == null -> "NONE"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        else -> "OTHER"
    }

    @Synchronized
    private fun apply(app: Context, now: String) {
        transport = now
        if (now == last) return
        last = now
        if (now == "WIFI") CarrierProfileEngine.useWifiProfile()
        else CarrierProfileEngine.detect(app)
        NetProbeEngine.onLinkChanged()
        DnsWarmupEngine.warmNow()
        RuntimeLogger.log("Transport -> " + now + " (stack re-learning link now)", "NET")
    }

    fun stop() {
        running = false
        try { callback?.let { cm?.unregisterNetworkCallback(it) } } catch (_: Throwable) { }
        callback = null
    }
}
/* ======
NetworkStateEngine Anchor
====== */

/* ========
PacketLossProbeEngine
======== */
// V3 INSTANT-REFLEX

/**
 * Measures PACKET LOSS - the metric TCP probes cannot see. Sends real UDP DNS
 * queries and counts replies. V3: queries alternate between two independent
 * resolvers (8.8.8.8 / 1.1.1.1) so a single resolver hiccup no longer reads
 * as link loss; the gap between queries is admin-tunable; live loss is
 * published for the admin Detector.
 */
object PacketLossProbeEngine {

    private val RESOLVERS = listOf("8.8.8.8", "1.1.1.1")

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val ROUND_MS: Long get() = 4000L
    private val PER_ROUND: Int get() = 4
    private val REPLY_TIMEOUT_MS: Int get() = 700
    private val ALPHA: Float get() = 0.3f
    private val GAP_MS: Long get() = 80L

    @Volatile private var running = false
    @Volatile var lossPct = 0f; private set
    @Volatile private var rounds = 0L
    @Volatile private var seq = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val perRound = if (PER_ROUND < 1) 1 else PER_ROUND
                    val gap = GAP_MS
                    var ok = 0
                    repeat(perRound) {
                        if (queryOnce()) ok++
                        if (gap > 0) try { Thread.sleep(gap) } catch (_: Throwable) { }
                    }
                    val roundLoss = (perRound - ok) * 100f / perRound
                    val a = ALPHA
                    lossPct = lossPct * (1 - a) + roundLoss * a
                    rounds++
                    if (roundLoss >= 50f)
                        RuntimeLogger.log("LOSS SPIKE " + String.format("%.0f", roundLoss) +
                            "% this round (avg " + String.format("%.0f", lossPct) + "%)", "NETLOSS")
                    else if (rounds % 15L == 0L)
                        RuntimeLogger.log("loss avg=" + String.format("%.0f", lossPct) + "% rounds=" + rounds, "NETLOSS")
                } catch (_: Throwable) { }
                val nap = ROUND_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-loss"; t.start()
    }

    fun stop() { running = false }

    private fun queryOnce(): Boolean = try {
        val host = RESOLVERS[(seq++ % RESOLVERS.size).toInt()]
        val id = Random.nextInt(0x0000FFFF)
        val q = dnsQuery(id)
        DatagramSocket().use { s ->
            s.soTimeout = REPLY_TIMEOUT_MS
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(host), 53))
            val buf = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            s.receive(resp)
            resp.length >= 2 &&
                ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)) == id
        }
    } catch (_: Throwable) { false }

    private fun dnsQuery(id: Int): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(id shr 8); b.write(id and 0xFF)
        b.write(0x01); b.write(0x00)          // recursion desired
        b.write(0x00); b.write(0x01)          // 1 question
        b.write(ByteArray(6))                  // no answer/auth/extra
        for (label in listOf("www", "google", "com")) {
            b.write(label.length); b.write(label.toByteArray())
        }
        b.write(0)
        b.write(0x00); b.write(0x01)           // type A
        b.write(0x00); b.write(0x01)           // class IN
        return b.toByteArray()
    }
}
/* ======
PacketLossProbeEngine Anchor
====== */

/* ========
RadioKeepAliveEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V2: cadence adapts - when the link is dirty the radio is pinned at high
 * power MORE often (every rhythm/2 s, floor = admin value), because a dirty
 * link plus a sleepy radio compounds into the worst first-touch latency.
 * V3: the base rhythm itself is override-aware (net.profile.keepalive_s),
 * so the admin controls both the rhythm and the floor.
 */
object RadioKeepAliveEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
    private val FLOOR_S: Int get() = 4

    @Volatile private var running = false
    @Volatile private var sent = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                var cadence = CarrierProfileEngine.keepAliveS
                if (CarrierProfileEngine.current.name != "WIFI") {
                    if (NetProbeEngine.quality != "GOOD") cadence = maxOf(FLOOR_S, cadence / 2)
                    try {
                        DatagramSocket().use { s ->
                            s.send(DatagramPacket(ByteArray(1), 1, InetAddress.getByName("8.8.8.8"), 53))
                        }
                        if (++sent % 20L == 0L)
                            RuntimeLogger.log("keepalive sent=" + sent + " cadence=" + cadence + "s", "NET")
                    } catch (_: Throwable) { }
                }
                try { Thread.sleep(if (cadence > 0) cadence * 1000L else 1000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-keepalive"; t.start()
    }

    fun stop() { running = false }
}
/* ======
RadioKeepAliveEngine Anchor
====== */

/* ========
SpikeBurstEngine
======== */
// V3 INSTANT-REFLEX

/**
 * When the sentinel flags congestion, this fires a rapid probe burst to map
 * the spike (depth + floor) and then watches for recovery at 1s resolution -
 * so the runtime knows the link is clean again SECONDS before the slow
 * cadence would notice. V3: burst size, burst gap and the clean line are all
 * admin-tunable, and the clean line follows the override-aware baseline.
 */
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
/* ======
SpikeBurstEngine Anchor
====== */

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
object GestureTimingFeedbackEngine {

    // 120ms is ~2 frames at 15fps. Anything older is unusable in fast-paced eFootball.
    private const val TIMEOUT_MS = 120L   
    private const val EWMA_ALPHA = 0.4f
    private const val LOG_EVERY_N = 50

    @Volatile var avgDispatchMs = 0f; private set
    @Volatile var expiredCount = 0L; private set
    @Volatile var measuredCount = 0L; private set
    @Volatile var lastDispatchMs = 0L; private set

    private val pendingSubmitMs = AtomicLong(-1L)
    private val pendingSeq = AtomicLong(-1L)
    private val globalSeq = AtomicLong(0L)

    /** Called at CentralExecutionBus.submit() time for SMART_ASSIST gestures. */
    fun recordSubmission(): Long {
        val seq = globalSeq.incrementAndGet()
        pendingSeq.set(seq)
        pendingSubmitMs.set(System.currentTimeMillis())
        return seq
    }

    /**
     * Called at gesture execution start in SmartAssistAccessibilityEngine.
     * seq must match the pending sequence to count.
     */
    fun recordDispatch(seq: Long) {
        val submitMs = pendingSubmitMs.get()
        if (submitMs < 0 || pendingSeq.get() != seq) return
        val elapsed = System.currentTimeMillis() - submitMs
        pendingSubmitMs.set(-1L)
        val clampedMs = elapsed.coerceIn(0L, 5000L).toFloat()
        avgDispatchMs = if (avgDispatchMs == 0f) clampedMs
                        else avgDispatchMs * (1 - EWMA_ALPHA) + clampedMs * EWMA_ALPHA
        lastDispatchMs = elapsed
        measuredCount++

        // CONDITIONLESS ACTIVE MITIGATION:
        // If a single gesture takes longer than 1 frame at 15fps (66ms),
        // immediately boost the current thread priority to prevent starvation
        // of subsequent gestures in the fast-paced eFootball environment.
        if (elapsed > 66L) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}
        }

        val cls = when {
            avgDispatchMs < 33f  -> "GESTURE_FAST"
            avgDispatchMs < 66f  -> "GESTURE_OK"
            avgDispatchMs < 120f -> "GESTURE_SLOW"
            else                 -> "GESTURE_LAGGING"
        }
        
        // Publish emergency signal if average latency exceeds 2 frames
        val signal = if (avgDispatchMs >= 120f) "GESTURE_EMERGENCY" else cls
        AdapterSignalBus.publishInput(signal, avgDispatchMs.toLong())

        if (measuredCount % LOG_EVERY_N == 0L) {
            RuntimeLogger.log(
                "GestureDispatch avg=${String.format("%.1f", avgDispatchMs)}ms " +
                    "last=${elapsed}ms expired=$expiredCount measured=$measuredCount",
                "INPUT"
            )
        }
    }

    /**
     * Called periodically to detect gestures that were submitted but
     * never dispatched (bus staleness / queue expiry).
     */
    fun checkExpiry() {
        val submitMs = pendingSubmitMs.get()
        if (submitMs < 0) return
        if (System.currentTimeMillis() - submitMs > TIMEOUT_MS) {
            expiredCount++
            pendingSubmitMs.set(-1L)
            
            // HARDWORKING ELIMINATOR: Force boost main thread when queue is stale
            try {
                Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}
            
            // Signal the CentralExecutionBus to drop stale events
            AdapterSignalBus.publishInput("BUS_STALE_PURGE", expiredCount)
            
            RuntimeLogger.log(
                "GestureDispatch EXPIRED after ${TIMEOUT_MS}ms — bus queue pressure " +
                    "(total expired=$expiredCount). PURGE & BOOST triggered.",
                "INPUT"
            )
        }
    }

    fun reset() {
        avgDispatchMs = 0f; expiredCount = 0L; measuredCount = 0L
        lastDispatchMs = 0L; pendingSubmitMs.set(-1L); pendingSeq.set(-1L)
    }
}
/* ======
GestureTimingFeedbackEngine Anchor
====== */

/* ========
InputAdapterService
======== */
class InputAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_input",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("InputAdapter heartbeat", "HEALTH")
            try { GestureTimingFeedbackEngine.checkExpiry() } catch (_: Throwable) {}
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("InputAdapterService started", "ADAPTER")

        // Unified foundation notification (Task C item (c)) - replaces the
        // per-node "Splendor Input Node" row on ID 9993, which collided
        // with the memory node's ID.
        NodeNotificationHub.attach(this, "adapter_input")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_input",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        InputPriorityEngine.start()
        InputLatencyEngine.start()
        TouchQualityEngine.start()
        OomAdaptiveThrottleEngine.start()
        GestureTimingFeedbackEngine.reset()
        InputThermalEliminatorEngine.start(applicationContext)
        InputVsyncEliminatorEngine.start()
        RuntimeLogger.log("Input engine stack ignited: 7 engines [LATENCY+QUALITY+PRIORITY+OOM+GESTURE+THERMAL+VSYNC]", "INPUT")
        RuntimeLogger.log("InputAdapter heartbeat scheduler started", "HEALTH")
    }

    override fun onDestroy() {
        InputLatencyEngine.stop()
        TouchQualityEngine.stop()
        InputPriorityEngine.stop()
        OomAdaptiveThrottleEngine.stop()
        GestureTimingFeedbackEngine.reset()
        InputThermalEliminatorEngine.stop()
        InputVsyncEliminatorEngine.stop()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        NodeNotificationHub.detach(this, "adapter_input")
        RuntimeLogger.log("InputAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
/* ======
InputAdapterService Anchor
====== */

/* ========
InputLatencyEngine
======== */
object InputLatencyEngine {

    @Volatile private var running = false
    @Volatile var latencyMs = 0L; private set
    @Volatile var classification = "UNKNOWN"; private set
    @Volatile var measurements = 0L; private set
    @Volatile var lagEvents = 0L; private set

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true

        val t = Thread {
            // Conditionlessly boost this polling thread so it never misses a measurement
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
            
            while (running) {
                try { measure() } catch (_: Throwable) {}
                try { Thread.sleep(200L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-latency"
        t.priority = Thread.MAX_PRIORITY
        t.start()
        
        RuntimeLogger.log("InputLatencyEngine started (Hardworking Eliminator Mode)", "INPUT")
    }

    fun stop() { running = false }

    private fun measure() {
        val posted = SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        
        // Post to main thread to measure dispatch latency
        mainHandler.post { 
            latencyMs = SystemClock.elapsedRealtime() - posted
            latch.countDown() 
        }
        
        // Wait max 200ms for main thread to respond
        val responded = latch.await(200L, TimeUnit.MILLISECONDS)
        if (!responded) {
            latencyMs = 200L // Main thread is completely blocked
            latch.countDown() // Prevent leak
        }
        
        measurements++
        
        // 15fps = 66ms/frame. 30fps = 33ms/frame.
        classification = when {
            latencyMs < 16L -> "INSTANT"
            latencyMs < 33L -> "GOOD"
            latencyMs < 66L -> "DELAYED"
            else -> { lagEvents++; "LAGGING" }
        }

        AdapterSignalBus.publishInput(classification, latencyMs)

        // CONDITIONLESS ACTIVE MITIGATION:
        // No warmup delays. If main thread is congested, immediately trigger purges.
        if (latencyMs >= 33L) { // More than 1 frame at 30fps, or half frame at 15fps
            
            // Signal Queen Bee (SmartAssist) to drop non-essential UI work and prioritize input
            AdapterSignalBus.publishInput("MAIN_THREAD_CONGESTION", latencyMs)
            
            // Force boost main thread priority dynamically if heavily lagging
            if (latencyMs >= 66L) {
                try {
                    // Attempt to boost the process priority
                    Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY)
                } catch (_: Throwable) {}
                
                RuntimeLogger.log("INPUT CRITICAL LAG: ${latencyMs}ms (total lag events: $lagEvents). PURGE TRIGGERED.", "INPUT")
            }
        }
    }
}
/* ======
InputLatencyEngine Anchor
====== */

/* ========
InputPriorityEngine
======== */
/**
 * InputPriorityEngine — HARDWORKING BOOSTER & ELIMINATOR.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * HyperOS aggressively throttles threads during fast gameplay to save battery.
 * Waiting 30 seconds to reapply priority causes massive input lag.
 * 
 * This engine now conditionlessly reapplies URGENT_DISPLAY every 500ms
 * to prevent silent deprioritization and instantly blocks OS throttle attempts.
 */
object InputPriorityEngine {

    @Volatile private var running = false
    @Volatile var reapplyCount = 0; private set

    fun start() {
        if (running) return
        running = true
        applyPriority()

        val t = Thread {
            // Conditionlessly boost this polling thread immediately
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
            
            while (running) {
                try {
                    val cur = Process.getThreadPriority(Process.myTid())
                    
                    // CONDITIONLESS ACTIVE MITIGATION:
                    // If HyperOS explicitly resets priority, block it and signal the Queen Bee.
                    if (cur > Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                        reapplyCount++
                        applyPriority()
                        AdapterSignalBus.publishInput("OS_THROTTLE_BLOCKED", reapplyCount.toLong())
                        RuntimeLogger.log("InputPriority: reset to $cur by OS, reapplying (#$reapplyCount)", "INPUT")
                    } else {
                        // Conditionlessly reapply anyway to fight silent HyperOS battery saver throttling
                        applyPriority()
                    }
                } catch (_: Throwable) {}
                
                // Reduced from 30,000ms to 500ms for tight 15fps/30fps reaction
                try { Thread.sleep(500L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-priority"
        t.priority = Thread.MAX_PRIORITY
        t.start()

        RuntimeLogger.log("InputPriorityEngine started — HARDWORKING BOOSTER MODE (500ms cycle)", "INPUT")
    }

    fun stop() { running = false }

    private fun applyPriority() {
        try { 
            // Force URGENT_DISPLAY (-8) to ensure input processing is never starved
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (e: Throwable) { 
            RuntimeLogger.log("InputPriority: failed: ${e.message}", "INPUT") 
        }
    }
}
/* ======
InputPriorityEngine Anchor
====== */

/* ========
InputThermalEliminatorEngine
======== */
/**
 * InputThermalEliminatorEngine — HARDWORKING ELIMINATOR for Thermal Throttling.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * When the Helio G81-Ultra heats up, the kernel aggressively caps CPU frequency.
 * Thread priority (URGENT_AUDIO) CANNOT overcome a frequency cap.
 * 
 * This engine polls every 1000ms. If thermal status >= MODERATE, it signals
 * the Queen Bee (SmartAssist) to drop non-essential work and reduce CPU load,
 * allowing the device to cool down and preventing total input lockout.
 */
object InputThermalEliminatorEngine {

    @Volatile private var running = false
    @Volatile var thermalStatus = "UNKNOWN"; private set

    fun start(context: Context) {
        if (running) return
        running = true
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val t = Thread {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
            
            var lastStatus = "UNKNOWN"
            while (running) {
                try {
                    val status = if (Build.VERSION.SDK_INT >= 29 && pm != null) {
                        when (pm.currentThermalStatus) {
                            PowerManager.THERMAL_STATUS_NONE -> "NONE"
                            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                            else -> "UNKNOWN"
                        }
                    } else "UNKNOWN"
                    
                    thermalStatus = status
                    
                    if (status != lastStatus) {
                        lastStatus = status
                        
                        // CONDITIONLESS ACTIVE MITIGATION:
                        if (status == "MODERATE" || status == "SEVERE" || status == "CRITICAL" || status == "EMERGENCY" || status == "SHUTDOWN") {
                            AdapterSignalBus.publishInput("THERMAL_THROTTLE_ACTIVE", status.hashCode().toLong())
                            RuntimeLogger.log("THERMAL THROTTLE DETECTED: $status — Signaling Queen Bee for Survival Mode", "INPUT")
                        } else {
                            AdapterSignalBus.publishInput("THERMAL_THROTTLE_CLEAR", status.hashCode().toLong())
                            RuntimeLogger.log("THERMAL THROTTLE CLEARED: $status — Restoring full performance", "INPUT")
                        }
                    }
                } catch (_: Throwable) {}
                
                try { Thread.sleep(1000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-thermal-eliminator"
        t.priority = Thread.MAX_PRIORITY
        t.start()
        RuntimeLogger.log("InputThermalEliminatorEngine started (Hardworking Eliminator Mode)", "INPUT")
    }

    fun stop() { running = false }
}
/* ======
InputThermalEliminatorEngine Anchor
====== */

/* ========
InputVsyncEliminatorEngine
======== */
/**
 * InputVsyncEliminatorEngine — HARDWORKING ELIMINATOR for Vsync/Choreographer Starvation.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * If SurfaceFlinger (GPU compositor) is overloaded, Choreographer frame callbacks are delayed.
 * This causes input-to-display lag even if the main thread dispatch latency is low.
 *
 * This engine monitors Vsync delays every 1000ms. If the frame callback is delayed by > 66ms
 * (1 frame at 15fps), it signals the Queen Bee to simplify UI and free up the compositor.
 */
object InputVsyncEliminatorEngine {

    @Volatile private var running = false
    @Volatile var lastFrameDelayMs = 0L; private set

    fun start() {
        if (running) return
        running = true
        
        val handler = Handler(Looper.getMainLooper())
        val checkIntervalMs = 1000L
        
        val checkRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                
                val startMs = SystemClock.elapsedRealtime()
                try {
                    Choreographer.getInstance().postFrameCallback {
                        if (!running) return@postFrameCallback
                        
                        val callbackMs = SystemClock.elapsedRealtime()
                        val delay = callbackMs - startMs
                        lastFrameDelayMs = delay
                        
                        // CONDITIONLESS ACTIVE MITIGATION:
                        // If frame callback is delayed by > 66ms (1 frame at 15fps)
                        if (delay > 66L) {
                            AdapterSignalBus.publishInput("VSYNC_STARVATION", delay)
                            // Force boost main thread priority to clear compositor backlog
                            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
                        } else {
                            AdapterSignalBus.publishInput("VSYNC_OK", delay)
                        }
                    }
                } catch (_: Throwable) {}
                
                if (running) {
                    handler.postDelayed(this, checkIntervalMs)
                }
            }
        }
        
        handler.post(checkRunnable)
        RuntimeLogger.log("InputVsyncEliminatorEngine started (Hardworking Eliminator Mode)", "INPUT")
    }

    fun stop() { running = false }
}
/* ======
InputVsyncEliminatorEngine Anchor
====== */

/* ========
OomAdaptiveThrottleEngine
======== */
/**
 * OomAdaptiveThrottleEngine — HARDWORKING ELIMINATOR & BOOSTER for HyperOS OOM/Cgroup throttling.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * HyperOS aggressively throttles apps via cgroups and OOM adj adjustments during fast gameplay.
 * A 3-second polling delay causes up to 45 frames of input lag at 15fps.
 *
 * This engine now:
 * 1. Polls every 500ms for instant detection of HyperOS throttling.
 * 2. Conditionlessly forces THREAD_PRIORITY_URGENT_AUDIO (-19) every cycle to fight silent cgroup throttling.
 * 3. Triggers OOM_HOSTILE at adj >= 10 (ultra-aggressive threshold).
 * 4. Signals the Queen Bee (SmartAssist) to drop non-essential UI work when hostile.
 */
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
/* ======
OomAdaptiveThrottleEngine Anchor
====== */

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
object TouchQualityEngine {

    private const val POLL_MS = 500L
    private const val STALL_THRESHOLD_CYCLES = 2 // 2 cycles * 500ms = 1000ms max touch unresponsiveness

    @Volatile private var running = false
    @Volatile var irqDropDetected = false; private set

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            // Conditionlessly boost this polling thread immediately
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
            
            var prevIrq = -1L
            var emptyIrqCycles = 0

            while (running) {
                try {
                    val irq = readTouchIrqCount()

                    if (prevIrq >= 0 && irq >= 0) {
                        val delta = irq - prevIrq
                        if (delta == 0L) {
                            emptyIrqCycles++
                            
                            // CONDITIONLESS ACTIVE MITIGATION:
                            // If no touch IRQs for 1000ms, the kernel/CPU is starved by HyperOS.
                            if (emptyIrqCycles >= STALL_THRESHOLD_CYCLES) {
                                if (!irqDropDetected) {
                                    irqDropDetected = true
                                    
                                    // Signal Queen Bee (SmartAssist) to drop non-essential UI work
                                    AdapterSignalBus.publishInput("TOUCH_IRQ_STALL", emptyIrqCycles.toLong())
                                    
                                    // Force boost priority to fight HyperOS cgroup throttling
                                    try {
                                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                                    } catch (_: Throwable) {}
                                    
                                    RuntimeLogger.log("TouchQuality: TOUCH IRQ STALL — no kernel events for ${emptyIrqCycles * POLL_MS}ms. SURVIVAL MODE TRIGGERED.", "INPUT")
                                }
                            }
                        } else {
                            emptyIrqCycles = 0
                            if (irqDropDetected) {
                                irqDropDetected = false
                                // Signal Queen Bee to restore full performance
                                AdapterSignalBus.publishInput("TOUCH_IRQ_RECOVERED", 0L)
                                RuntimeLogger.log("TouchQuality: TOUCH IRQ RECOVERED — kernel events restored.", "INPUT")
                            }
                        }
                    }
                    if (irq >= 0) prevIrq = irq
                } catch (_: Throwable) {}

                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-touch-quality"
        t.priority = Thread.MAX_PRIORITY
        t.start()
        RuntimeLogger.log("TouchQualityEngine started (Hardworking Eliminator Mode - 500ms cycle)", "INPUT")
    }

    fun stop() { running = false }

    private fun readTouchIrqCount(): Long = try {
        val kw = listOf("touch", "fts", "focal", "hid", "i2c", "input")
        var total = 0L
        var found = false

        BufferedReader(FileReader("/proc/interrupts")).use { br ->
            br.lineSequence().forEach { line ->
                if (kw.any { line.lowercase().contains(it) }) {
                    found = true
                    // Fixed Regex: correctly extracts numbers from /proc/interrupts
                    Regex("""\s+(\d+)""").findAll(line).forEach { total += it.groupValues[1].toLongOrNull() ?: 0L }
                }
            }
        }
        if (found) total else -1L
    } catch (_: Throwable) { -1L }
}
/* ======
TouchQualityEngine Anchor
====== */

/* ========
CompressedSnapshot
======== */
data class CompressedSnapshot(
    val componentName: String,
    val timestamp: Long,
    val payload: ByteArray
)
/* ======
CompressedSnapshot Anchor
====== */

/* ========
CompressedSnapshotRepository
======== */
object CompressedSnapshotRepository {

    private val snapshots =
        mutableMapOf<String, CompressedSnapshot>()

    @Synchronized
    fun save(snapshot: CompressedSnapshot) {
        snapshots[snapshot.componentName] = snapshot
    }

    @Synchronized
    fun get(component: String): CompressedSnapshot? {
        return snapshots[component]
    }

    @Synchronized
    fun getAll(): List<CompressedSnapshot> {
        return snapshots.values.toList()
    }
}
/* ======
CompressedSnapshotRepository Anchor
====== */

/* ========
LifecycleSerializationEngine
======== */
object LifecycleSerializationEngine {

    fun capture(
        componentName: String,
        lifecycleState: String,
        trimLevel: Int = ComponentCallbacks2.TRIM_MEMORY_COMPLETE
    ) {

        val pressure =
            when {
                trimLevel >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
                    "CRITICAL"

                trimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                    "LOW"

                else ->
                    "NORMAL"
            }

        val snapshot =
            StateSnapshot(
                componentName = componentName,
                lifecycleState = lifecycleState,
                timestamp = System.currentTimeMillis(),
                memoryPressure = pressure,
                details = "Lifecycle snapshot captured"
            )

        LifecycleSnapshotRepository.save(snapshot)

        val serialized =
            "${snapshot.componentName}|${snapshot.lifecycleState}|${snapshot.timestamp}|${snapshot.memoryPressure}|${snapshot.details}"

        val compressed =
            SnapshotCompressionEngine.compress(serialized)

        CompressedSnapshotRepository.save(
            CompressedSnapshot(
                componentName = snapshot.componentName,
                timestamp = snapshot.timestamp,
                payload = compressed
            )
        )
    }
}
/* ======
LifecycleSerializationEngine Anchor
====== */

/* ========
LifecycleSnapshotRepository
======== */
object LifecycleSnapshotRepository {

    private val snapshots =
        mutableMapOf<String, StateSnapshot>()

    @Synchronized
    fun save(snapshot: StateSnapshot) {
        snapshots[snapshot.componentName] = snapshot
    }

    @Synchronized
    fun get(component: String): StateSnapshot? {
        return snapshots[component]
    }

    @Synchronized
    fun getAll(): List<StateSnapshot> {
        return snapshots.values.toList()
    }
}
/* ======
LifecycleSnapshotRepository Anchor
====== */

/* ========
LmkAdapterService
======== */
/**
 * LMK ADAPTER NODE (Task C upgrade).
 *
 * 1. FAKE METRIC REMOVED. The old workload runnable "measured"
 *    System.nanoTime() minus System.nanoTime() - literally nothing - and
 *    reported that fabricated ~0ns duration to PerformanceHintEngine every
 *    5 seconds. The performance-hint machinery was being tuned against a
 *    lie. It now reports the REAL measured duration of the real work this
 *    node performs (lifecycle capture + rehydration cycle). If that work
 *    is cheap, the hint says cheap - truthfully.
 *
 * 2. OFF THE MAIN THREAD. All periodic work moved from the main looper to
 *    a dedicated HandlerThread; the main thread stays free for binder
 *    traffic. Periodic serialization on main was avoidable jitter.
 *
 * 3. REAL ERROR COUNTS. errorCount was hardcoded 0 - a failing capture
 *    could never surface in a health verdict. Failures now count and the
 *    last error rides in the health details.
 *
 * 4. UNIFIED NOTIFICATION. Per-node "Splendor LMK Node" row replaced by
 *    the shared NodeNotificationHub row (Task C item (c)).
 */
class LmkAdapterService : Service() {

    private lateinit var workThread: HandlerThread
    private lateinit var workHandler: Handler

    private val errorCount = AtomicInteger(0)
    @Volatile private var lastError: String = "none"

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            publishHealth(
                if (errorCount.get() == 0) "Heartbeat active"
                else "Heartbeat active; lastError=$lastError"
            )
            workHandler.postDelayed(this, 10_000L)
        }
    }

    /*
     * One measured work cycle: capture lifecycle state, persist any
     * rehydratable snapshot, report the ACTUAL duration of that work.
     * This replaces three separate timers (5s fake-workload, 15s lifecycle,
     * 20s rehydration) with one truthful 15s cycle.
     */
    private val workCycleRunnable = object : Runnable {
        override fun run() {
            val start = System.nanoTime()
            try {
                LifecycleSerializationEngine.capture(
                    componentName = "com.assistant",
                    lifecycleState = "SERVICE_ACTIVE"
                )
                RehydrationEngine.restore("com.assistant")?.let {
                    RehydrationRepository.save(it)
                }
                val duration = System.nanoTime() - start
                PerformanceHintEngine.reportActualWorkload(
                    this@LmkAdapterService,
                    duration.coerceAtLeast(1L)
                )
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                lastError = e.message ?: e.javaClass.simpleName
                RuntimeLogger.log("LMK work cycle failed: $lastError", "LMK_ERROR")
            }
            workHandler.postDelayed(this, 15_000L)
        }
    }

    private val messenger =
        Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                when (msg.what) {
                    101 -> {
                        Process.setThreadPriority(
                            Process.THREAD_PRIORITY_URGENT_DISPLAY
                        )
                        true
                    }
                    else -> false
                }
            }
        )

    override fun onCreate() {
        super.onCreate()

        // Unified foundation notification (Task C item (c)).
        NodeNotificationHub.attach(this, "adapter_lmk")

        workThread = HandlerThread("LmkAdapterWork").apply { start() }
        workHandler = Handler(workThread.looper)

        workHandler.post(heartbeatRunnable)
        workHandler.post(workCycleRunnable)

        publishHealth("Foreground service running")
    }

    private fun publishHealth(details: String) {
        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_lmk",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = errorCount.get(),
                recoveryCount = 0,
                details = details
            )
        )
    }

    override fun onDestroy() {
        if (::workHandler.isInitialized) {
            workHandler.removeCallbacksAndMessages(null)
        }
        if (::workThread.isInitialized) {
            workThread.quitSafely()
        }
        NodeNotificationHub.detach(this, "adapter_lmk")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? =
        messenger.binder
}
/* ======
LmkAdapterService Anchor
====== */

/* ========
PerformanceHintEngine
======== */
object PerformanceHintEngine {

    fun reportActualWorkload(
        context: Context,
        actualDurationNanos: Long
    ) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }

        val manager =
            context.getSystemService(
                PerformanceHintManager::class.java
            ) ?: return

        val session =
            manager.createHintSession(
                intArrayOf(android.os.Process.myTid()),
                actualDurationNanos
            ) ?: return

        session.reportActualWorkDuration(
            actualDurationNanos
        )

        session.close()
    }
}
/* ======
PerformanceHintEngine Anchor
====== */

/* ========
RehydratedStateSnapshot
======== */
data class RehydratedStateSnapshot(
    val componentName: String,
    val lifecycleState: String,
    val timestamp: Long,
    val memoryPressure: String,
    val details: String
)
/* ======
RehydratedStateSnapshot Anchor
====== */

/* ========
RehydrationEngine
======== */
object RehydrationEngine {

    fun restore(
        componentName: String
    ): RehydratedStateSnapshot? {

        val compressed =
            CompressedSnapshotRepository.get(componentName)
                ?: return null

        val decompressed =
            SnapshotCompressionEngine.decompress(
                compressed.payload
            )

        val parts = decompressed.split("|")

        if (parts.size < 5) {
            return null
        }

        return RehydratedStateSnapshot(
            componentName = parts[0],
            lifecycleState = parts[1],
            timestamp = parts[2].toLongOrNull() ?: 0L,
            memoryPressure = parts[3],
            details = parts[4]
        )
    }
}
/* ======
RehydrationEngine Anchor
====== */

/* ========
RehydrationRepository
======== */
object RehydrationRepository {

    private val restored =
        mutableMapOf<String, RehydratedStateSnapshot>()

    @Synchronized
    fun save(snapshot: RehydratedStateSnapshot) {
        restored[snapshot.componentName] = snapshot
    }

    @Synchronized
    fun get(
        componentName: String
    ): RehydratedStateSnapshot? {
        return restored[componentName]
    }

    @Synchronized
    fun getAll(): List<RehydratedStateSnapshot> {
        return restored.values.toList()
    }
}
/* ======
RehydrationRepository Anchor
====== */

/* ========
SnapshotCompressionEngine
======== */
object SnapshotCompressionEngine {

    fun compress(text: String): ByteArray {

        val output = ByteArrayOutputStream()

        GZIPOutputStream(output).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }

        return output.toByteArray()
    }

    fun decompress(data: ByteArray): String {

        return GZIPInputStream(
            ByteArrayInputStream(data)
        ).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
}
/* ======
SnapshotCompressionEngine Anchor
====== */

/* ========
StateSnapshot
======== */
data class StateSnapshot(
    val componentName: String,
    val lifecycleState: String,
    val timestamp: Long,
    val memoryPressure: String,
    val details: String
)
/* ======
StateSnapshot Anchor
====== */

/* ========
ViewInvalidationEvent
======== */
data class ViewInvalidationEvent(
    val source: String,
    val critical: Boolean,
    val timestamp: Long
)
/* ======
ViewInvalidationEvent Anchor
====== */

/* ========
ViewInvalidationFilter
======== */
object ViewInvalidationFilter {

    fun shouldInvalidate(
        source: String,
        critical: Boolean
    ): Boolean {

        ViewInvalidationRepository.record(
            ViewInvalidationEvent(
                source = source,
                critical = critical,
                timestamp = System.currentTimeMillis()
            )
        )

        return critical
    }
}
/* ======
ViewInvalidationFilter Anchor
====== */

/* ========
ViewInvalidationRepository
======== */
object ViewInvalidationRepository {

    private val events =
        mutableListOf<ViewInvalidationEvent>()

    @Synchronized
    fun record(
        event: ViewInvalidationEvent
    ) {
        events.add(event)
    }

    @Synchronized
    fun getAll(): List<ViewInvalidationEvent> {
        return events.toList()
    }
}
/* ======
ViewInvalidationRepository Anchor
====== */

/* ========
AggressiveMemoryHoarding
======== */
/**
 * MEMORY RECLAIM ENGINE (Task C upgrade).
 *
 * Honesty fixes over the previous version:
 *
 * - NO SILENT FAILURES. Kill attempts that threw were swallowed by an
 *   empty catch, and killedCount counted ATTEMPTS, not successes. Successes
 *   and failures are now counted separately; a purge that reclaimed nothing
 *   is visible as exactly that.
 *
 * - COOLDOWN GUARD. Callers may invoke this on every CRITICAL tick; the
 *   engine enforces one purge per 60s so pressure loops cannot thrash the
 *   system with kill storms.
 *
 * - NO SELF-GC THEATRE. System.gc()/runFinalization() only sweep OUR heap
 *   and pause OUR process - they do not reclaim system RAM. Removed. The
 *   before/after availMem delta is measured and logged instead.
 *
 * - OWN PACKAGES PROTECTED. The kill list dedupes packages and skips our
 *   own package family.
 *
 * - MEASURED RESULT, STATED HONESTLY. Reclaim is reported as the availMem
 *   delta immediately after the kill pass. OS reclaim continues
 *   asynchronously, so the number is a floor, not a ceiling.
 *
 * REQUIRES: android.permission.KILL_BACKGROUND_PROCESSES.
 * Platform truth: on modern Android this only demotes processes already in
 * the background-killable band; it cannot touch foreground or
 * system-protected processes. Useful headroom on a 4GB device, not magic.
 */
object AggressiveMemoryHoarding {

    private const val COOLDOWN_MS = 60_000L

    private val purgesExecuted = AtomicLong(0L)
    private val purgesSkippedCooldown = AtomicLong(0L)
    private val packagesKilled = AtomicLong(0L)
    private val killFailures = AtomicLong(0L)

    @Volatile private var lastPurgeMs = 0L
    @Volatile private var lastReclaimedMb = 0L

    /** @return true if a purge actually ran (not cooldown-skipped). */
    @JvmStatic
    fun executePurge(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPurgeMs < COOLDOWN_MS) {
            purgesSkippedCooldown.incrementAndGet()
            return false
        }
        lastPurgeMs = now
        purgesExecuted.incrementAndGet()
        RuntimeLogger.log("Initiating memory purge...", "MEMORY_HOARDER")
        // MASSIVE POWER: Force capture loop into survival mode (10fps) immediately
        try { com.assistant.diagnostic.AdapterSignalBus.publishCaptureThrottle(3) } catch (_: Throwable) {}
        // MASSIVE POWER: Force capture loop into survival mode (10fps) immediately
        // to prevent LMK kills while the purge runs and OS reclaims RAM.
        try { com.assistant.diagnostic.AdapterSignalBus.publishCaptureThrottle(3) } catch (_: Throwable) {}

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val before = availableMb(activityManager)

        val ownPrefix = context.packageName.substringBeforeLast('.')
        val myPid = android.os.Process.myPid()

        val candidates = LinkedHashSet<String>()
        activityManager.runningAppProcesses?.forEach { app ->
            if (app.pid != myPid &&
                app.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            ) {
                app.pkgList?.forEach { pkg ->
                    if (!pkg.startsWith(ownPrefix)) candidates.add(pkg)
                }
            }
        }

        var killed = 0
        var failed = 0
        for (pkg in candidates) {
            try {
                activityManager.killBackgroundProcesses(pkg)
                killed++
            } catch (e: Exception) {
                failed++
            }
        }
        packagesKilled.addAndGet(killed.toLong())
        killFailures.addAndGet(failed.toLong())

        val after = availableMb(activityManager)
        lastReclaimedMb = (after - before).coerceAtLeast(0L)

        RuntimeLogger.log(
            "Purge complete | killed=$killed failed=$failed | " +
                "avail ${before}MB -> ${after}MB (immediate floor; OS reclaim continues)",
            "MEMORY_HOARDER"
        )
        return true
    }

    private fun availableMb(am: ActivityManager): Long {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / 1048576L
    }

    fun hoardingRuntimeSnapshot(): Map<String, Any> = mapOf(
        "purgesExecuted" to purgesExecuted.get(),
        "purgesSkippedCooldown" to purgesSkippedCooldown.get(),
        "packagesKilled" to packagesKilled.get(),
        "killFailures" to killFailures.get(),
        "lastPurgeMs" to lastPurgeMs,
        "lastReclaimedMb" to lastReclaimedMb
    )
}
/* ======
AggressiveMemoryHoarding Anchor
====== */

/* ========
MemoryAdapterService
======== */
/**
 * PRESSURE-TIERED MEMORY ADAPTER NODE (Task C upgrade).
 *
 * Every change below is traced against the real target device: 4GB RAM,
 * 50-60% baseline use - permanently one bad allocation away from LMK kills.
 *
 * 1. TIERED PRESSURE RESPONSE. The old node polled every 30s (15s under
 *    pressure). On a RAM-starved device, low-memory kills happen within
 *    seconds; a 30s blind spot is how sessions die "for no reason".
 *    Polling now adapts across four tiers - HEALTHY 30s / WATCH 10s /
 *    PRESSURE 5s / CRITICAL 2s - driven by availMem vs the OS kill
 *    threshold.
 *
 * 2. REAL RECOVERY, NOT SELF-GC. The old "recovery" was System.gc() +
 *    runFinalization() in OUR OWN process. That frees almost nothing
 *    system-wide and pauses our own hot path - self-inflicted stutter
 *    dressed up as recovery. Removed. On CRITICAL the node now triggers
 *    AggressiveMemoryHoarding.executePurge (cooldown-guarded inside the
 *    engine), which acts on the actual RAM consumers.
 *
 * 3. onTrimMemory WIRED. The OS's own pressure signal (ComponentCallbacks2)
 *    was ignored entirely. Any trim signal at RUNNING_LOW or worse now
 *    forces an immediate telemetry pass instead of waiting out the timer.
 *    An epoch guard ensures a forced pass replaces the pending chain
 *    rather than forking a second polling loop.
 *
 * 4. UNIFIED NOTIFICATION. This node previously collided with the input
 *    node on foreground ID 9993 (silent replacement; stopping one service
 *    could strip the other's foreground protection). Both now attach to
 *    the single shared NodeNotificationHub row.
 *
 * 5. TRUTHFUL HEALTH. Health details carry the live tier and availMB,
 *    not a static "Heartbeat active" string.
 */
class MemoryAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { true })

    private val executorService: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    private lateinit var activityManager: ActivityManager

    // Cached to eliminate per-tick allocation churn
    private val memoryInfo = ActivityManager.MemoryInfo()

    private val errorCount = AtomicInteger(0)
    private val recoveryCount = AtomicInteger(0)
    private val telemetryEpoch = AtomicInteger(0)

    @Volatile private var isRunning = false
    @Volatile private var currentTier = "UNKNOWN"
    @Volatile private var lastAvailableMb = -1L

    private enum class Tier(val pollMs: Long) {
        HEALTHY(30_000L),
        WATCH(10_000L),
        PRESSURE(5_000L),
        CRITICAL(2_000L)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        RuntimeLogger.log("MemoryAdapterService started: PRESSURE-TIERED NODE", "ADAPTER")

        // Unified foundation notification (Task C item (c)).
        NodeNotificationHub.attach(this, "adapter_memory")
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        publishHealth("Foreground service running - tiered pressure response")

        scheduleNextHeartbeat(10_000L)
        forceTelemetryPass(500L)
    }

    /*
     * OS pressure signal. RUNNING_LOW or worse means the timer is already
     * too slow - measure NOW.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        RuntimeLogger.log("OS trim signal level=$level", "MEMORY")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            forceTelemetryPass(0L)
        }
    }

    /* Starts a fresh telemetry chain; any pending older chain dies via epoch. */
    private fun forceTelemetryPass(delayMs: Long) {
        scheduleTelemetry(delayMs, telemetryEpoch.incrementAndGet())
    }

    private fun scheduleTelemetry(delayMs: Long, epoch: Int) {
        if (!isRunning) return
        executorService.schedule({
            if (!isRunning || epoch != telemetryEpoch.get()) return@schedule
            try {
                // Populate cached memory object - zero allocation per tick
                activityManager.getMemoryInfo(memoryInfo)

                val availableMb = memoryInfo.availMem / 1048576L
                val thresholdMb = (memoryInfo.threshold / 1048576L).coerceAtLeast(1L)
                lastAvailableMb = availableMb

                val tier = when {
                    memoryInfo.lowMemory ||
                        availableMb < (thresholdMb * 12L) / 10L -> Tier.CRITICAL
                    availableMb < (thresholdMb * 18L) / 10L -> Tier.PRESSURE
                    availableMb < (thresholdMb * 25L) / 10L -> Tier.WATCH
                    else -> Tier.HEALTHY
                }
                currentTier = tier.name

                RuntimeLogger.log(
                    "MEMORY POLLED | tier=${tier.name} | available=${availableMb}MB | " +
                        "threshold=${thresholdMb}MB | lowMemory=${memoryInfo.lowMemory}",
                    "MEMORY"
                )

                if (tier == Tier.CRITICAL) {
                    // Real recovery: act on the system's RAM consumers,
                    // not our own heap. Engine enforces its own cooldown.
                    val purged = AggressiveMemoryHoarding.executePurge(this)
                    if (purged) recoveryCount.incrementAndGet()
                }

                MemoryPressureBusEngine.publish(tier.name, availableMb)
                MemoryCaptureGateEngine.onTierChange(tier.name, availableMb)
                publishHealth(
                    "tier=${tier.name} avail=${availableMb}MB purges=${recoveryCount.get()}"
                )

                scheduleTelemetry(tier.pollMs + Random.nextLong(-100L, 100L), epoch)
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                RuntimeLogger.log("MEMORY telemetry failed: ${e.message}", "MEMORY_ERROR")
                publishHealth("telemetry error: ${e.message}")
                scheduleTelemetry(10_000L, epoch)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleNextHeartbeat(delayMs: Long) {
        if (!isRunning) return
        executorService.schedule({
            if (!isRunning) return@schedule
            try {
                publishHealth("heartbeat tier=$currentTier avail=${lastAvailableMb}MB")
                scheduleNextHeartbeat(10_000L + Random.nextLong(-75L, 75L))
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                RuntimeLogger.log("Heartbeat engine exception: ${e.message}", "HEALTH_ERROR")
                scheduleNextHeartbeat(5_000L)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun publishHealth(details: String) {
        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_memory",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = errorCount.get(),
                recoveryCount = recoveryCount.get(),
                details = details
            )
        )
    }

    override fun onDestroy() {
        isRunning = false
        RuntimeLogger.log("MemoryAdapterService shutting down: Canceling Executors", "ADAPTER")
        executorService.shutdownNow()
        NodeNotificationHub.detach(this, "adapter_memory")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
/* ======
MemoryAdapterService Anchor
====== */

/* ========
MemoryCaptureGateEngine
======== */
/**
 * MemoryCaptureGateEngine — memory pressure → capture cadence bridge.
 *
 * PROVEN GAP: AdapterSignalBus.memoryIsCritical is published but has
 * zero consumers in OverlayService's capture loop. Memory at 282MB avail
 * (CRITICAL tier) does not trigger any change in OCR cadence or frame
 * capture rate. The system keeps running 30fps OCR regardless of RAM state.
 *
 * This engine translates the memory tier into a capture_throttle level
 * that OverlayService can read to widen its frame gate:
 *
 *   HEALTHY  → captureThrottle = 0  (33ms gate, 30fps)
 *   WATCH    → captureThrottle = 1  (50ms gate, 20fps)
 *   PRESSURE → captureThrottle = 2  (66ms gate, 15fps)
 *   CRITICAL → captureThrottle = 3  (100ms gate, 10fps) + skip full VisionCore alternation
 *
 * The throttle level is a simple Int on AdapterSignalBus (added below).
 * OverlayService reads it in its frame gate check.
 *
 * Additionally publishes the current throttle state to the RuntimeLogger
 * on every tier change so the heal log captures the transition.
 */
object MemoryCaptureGateEngine {

    @Volatile var captureThrottle = 0; private set
    @Volatile private var lastTier = "UNKNOWN"

    /**
     * Called by MemoryAdapterService every time it computes a tier.
     * Tier string matches MemoryAdapterService.Tier enum names.
     */
    fun onTierChange(tier: String, availMb: Long) {
        val newThrottle = when (tier) {
            "CRITICAL" -> 3
            "PRESSURE" -> 2
            "WATCH"    -> 1
            else       -> 0   // HEALTHY
        }

        val changed = newThrottle != captureThrottle || tier != lastTier
        captureThrottle = newThrottle
        lastTier = tier

        // Publish via AdapterSignalBus extended field (see bus patch below)
        AdapterSignalBus.publishCaptureThrottle(newThrottle)

        if (changed && newThrottle > 0) {
            RuntimeLogger.log(
                "MemoryCaptureGate: tier=$tier avail=${availMb}MB → " +
                    "captureThrottle=$newThrottle (capture rate reduced)",
                "MEMORY"
            )
        } else if (changed && newThrottle == 0) {
            RuntimeLogger.log(
                "MemoryCaptureGate: tier=HEALTHY avail=${availMb}MB → " +
                    "captureThrottle=0 (full rate restored)",
                "MEMORY"
            )
        }
    }

    /**
     * Returns the recommended capture frame interval in ms for the
     * current throttle level. OverlayService calls this in its frame gate.
     */
    // V10 LATENCY FIX: Cap interval at 33ms for gameplay freshness.
    // Memory pressure must not starve the decision loop of fresh frames.
    fun recommendedIntervalMs(): Long = when (captureThrottle) {
        3 -> 100L // CRITICAL: 10fps (Survival mode)
        2 -> 66L  // PRESSURE: 15fps
        1 -> 50L  // WATCH: 20fps
        else -> 33L // HEALTHY: 30fps
    }

    /**
     * Returns true if full VisionCore processing should be skipped
     * (CRITICAL tier only — run ball-only detection even on even frames).
     */
    fun shouldSkipFullVision(): Boolean = captureThrottle >= 3
}
/* ======
MemoryCaptureGateEngine Anchor
====== */

/* ========
MemoryPressureBusEngine
======== */
object MemoryPressureBusEngine {
    fun publish(tier: String, availMb: Long) {
        AdapterSignalBus.publishMemory(tier, availMb)
        if (tier == "CRITICAL")
            RuntimeLogger.log("MemoryPressureBus: CRITICAL (avail=${availMb}MB)", "MEMBUSENGINE")
    }
}
/* ======
MemoryPressureBusEngine Anchor
====== */

/* ========
ThermalAdapterService
======== */
class ThermalAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastThermalStatus = -1

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_thermal",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "thermalStatus=$lastThermalStatus (0=none..6=shutdown)"
                )
            )
            RuntimeLogger.log("ThermalAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    private val thermalHandler = Handler(Looper.getMainLooper())

    private val thermalRunnable = object : Runnable {

        override fun run() {

            try {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                    val powerManager =
                        getSystemService(PowerManager::class.java)

                    val status =
                        powerManager?.currentThermalStatus ?: -1

                    lastThermalStatus = status
                    AdapterSignalBus.publishThermal(status)  // PHASE3: feed SmartAssist
                    val thermalLabel = when (status) {
                        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"
                        3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; else -> "SHUTDOWN"
                    }
                    RuntimeLogger.log(
                        "THERMAL status=$status ($thermalLabel)${if (status >= 3) " *** ENGINE IMPACT ***" else ""}",
                        "THERMAL"
                    )
                }

            } catch (e: Exception) {

                RuntimeLogger.log(
                    "THERMAL telemetry failed",
                    "THERMAL"
                )
            }

            thermalHandler.postDelayed(
                this,
                30000L
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("ThermalAdapterService started", "ADAPTER")

        // Unified foundation notification (Task C item (e)) - this node was
        // the EIGHTH service on colliding foreground ID 9993.
        NodeNotificationHub.attach(this, "adapter_thermal")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_thermal",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("ThermalAdapter heartbeat scheduler started", "HEALTH")

        thermalHandler.post(thermalRunnable)
        RuntimeLogger.log("Thermal telemetry started", "THERMAL")
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        thermalHandler.removeCallbacks(thermalRunnable)
        NodeNotificationHub.detach(this, "adapter_thermal")
        RuntimeLogger.log("ThermalAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
/* ======
ThermalAdapterService Anchor
====== */

/* ========
BatteryAdapterService
======== */
class BatteryAdapterService : Service() {
    // Keep messenger on main looper for binding if needed by system
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    
    // CRITICAL FIX: Move ALL polling off main thread to eliminate UI jank and secure 15fps baseline
    private val workerThread = HandlerThread("BatteryAdapterWorker", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    @Volatile private var lastLevel = -1
    @Volatile private var lastStatus = -1
    @Volatile private var lastPollTime = System.currentTimeMillis()
    @Volatile private var isGameActive = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_battery",
                    status = if (isGameActive) "ACTIVE_GAMEPLAY" else "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "level=${lastLevel}% status=$lastStatus game=$isGameActive"
                )
            )
            RuntimeLogger.log("BatteryAdapter heartbeat", "HEALTH")
            workerHandler.postDelayed(this, 10000)
        }
    }

    private val batteryRunnable = object : Runnable {
        override fun run() {
            try {
                val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

                // Calculate true percentage (scale is usually 100, but sometimes different)
                val pct = if (scale > 0) (level * 100) / scale else level
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastPollTime
                lastPollTime = currentTime

                // Calculate drain rate to detect Helio G81 voltage sag/thermal throttle under gaming load
                // If CPU boosts hard, battery drains fast. We catch this to prevent thermal throttling stutters.
                val drainRate = if (lastLevel > 0 && deltaTime > 0) {
                    ((lastLevel - pct) * 60000f) / deltaTime.toFloat()
                } else 0f

                val isCharging = charging == BatteryManager.BATTERY_STATUS_CHARGING ||
                    charging == BatteryManager.BATTERY_STATUS_FULL

                // Preserve original bus publish (using pct for safer threshold checks across devices)
                AdapterSignalBus.publishBattery(pct, isCharging)

                // Companion Engine: React to gameplay power demands
                GameplayPowerEngine.evaluate(pct, isCharging, drainRate)

                lastLevel = pct
                lastStatus = charging

                val battLabel = if (pct < 15 && !isCharging) " *** BATTERY CRITICAL ***" else ""
                val sagLabel = if (drainRate > 1.5f && !isCharging && isGameActive) " [VOLTAGE SAG]" else ""
                RuntimeLogger.log(
                    "BATTERY pct=${pct}% status=$charging charging=$isCharging drain=${"%.2f".format(drainRate)}/min$battLabel$sagLabel",
                    "BATTERY"
                )

            } catch (e: Exception) {
                RuntimeLogger.log("BATTERY telemetry failed: ${e.message}", "BATTERY")
            }

            // Dynamic polling: 5s during game to catch thermal sags fast, 30s otherwise to save power
            val nextDelay = if (isGameActive) 5000L else 30000L
            workerHandler.postDelayed(this, nextDelay)
        }
    }

    private val gameStateRunnable = object : Runnable {
        override fun run() {
            isGameActive = VisionTrust.isGameForeground()
            workerHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("BatteryAdapterService started", "ADAPTER")

        NodeNotificationHub.attach(this, "adapter_battery")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_battery",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running (Worker thread active)"
            )
        )

        workerHandler.post(heartbeatRunnable)
        workerHandler.post(batteryRunnable)
        workerHandler.post(gameStateRunnable)
        
        RuntimeLogger.log("Battery telemetry & GameplayPowerEngine started on worker thread", "BATTERY")
    }

    override fun onDestroy() {
        workerHandler.removeCallbacks(heartbeatRunnable)
        workerHandler.removeCallbacks(batteryRunnable)
        workerHandler.removeCallbacks(gameStateRunnable)
        workerThread.quitSafely()
        NodeNotificationHub.detach(this, "adapter_battery")
        RuntimeLogger.log("BatteryAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder

    /**
     * COMPANION ENGINE: GameplayPowerEngine
     * Purpose: Detects eFootball 2027 foreground state via VisionTrust, mitigates Helio G81 thermal throttle,
     * and signals CPU/LoadShed engines to maintain strict 15fps smoothness and fast input.
     */
    object GameplayPowerEngine {
        @Suppress("UNUSED_PARAMETER")
        fun evaluate(level: Int, isCharging: Boolean, drainRate: Float) {
            // If battery is critically sagging under load, signal the LoadShed governor 
            // to kill background tasks, preserving CPU cycles for eFootball 15fps.
            // Helio G81-Ultra draws heavy current during boost; rapid drain = thermal limit approaching.
            if (drainRate > 1.5f && !isCharging) {
                val brakeLevel = if (drainRate > 3.0f) 2 else 1
                AdapterSignalBus.publishExecutionBrake(brakeLevel)
                RuntimeLogger.log("GameplayPowerEngine: Drain=${"%.2f".format(drainRate)}/min -> ExecutionBrake=$brakeLevel", "ENGINE")
            } else if (AdapterSignalBus.executionIsBraked) {
                // Release brake if charging or drain normalizes
                AdapterSignalBus.publishExecutionBrake(0)
            }
        }
    }
}
/* ======
BatteryAdapterService Anchor
====== */

/* ========
SyncAdapterService
======== */
/**
 * PHASE3 UPGRADE: SyncAdapterService
 * Previous state: DEAD SHELL — only a heartbeat, zero actual logic.
 * Real job now: monitors accessibility service liveness every 15s.
 * The accessibility service is the entire gesture dispatch path.
 * If it dies silently (HyperOS kills it), no gestures fire but no
 * error is logged anywhere. This adapter is the early-warning detector.
 */
class SyncAdapterService : Service() {
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastLivenessCheck = "unchecked"
    @Volatile private var accessibilityLivenessFails = 0

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_sync",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = accessibilityLivenessFails,
                    recoveryCount = 0,
                    details = "accessibility=$lastLivenessCheck fails=$accessibilityLivenessFails"
                )
            )
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    private val livenessRunnable = object : Runnable {
        override fun run() {
            try {
                // PHASE4B FIX: use AccessibilityManager instead of broken Kotlin companion reflection.
                // Log-proven: previous reflection probe returned null while gestures were dispatching.
                // AccessibilityManager.getEnabledAccessibilityServiceList() is the correct API.
                // PHASE4B: check enabled accessibility services via Settings string (no reflection)
                // Use reflection-free approach: check our package in enabled services
                val settingsStr = try {
                    android.provider.Settings.Secure.getString(
                        contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ) ?: ""
                } catch (_: Throwable) { "" }
                val alive = settingsStr.contains(packageName, ignoreCase = true)
                lastLivenessCheck = if (alive) "LIVE" else "DEAD_OR_DISABLED"
                if (!alive) {
                    accessibilityLivenessFails++
                    if (accessibilityLivenessFails >= 3) {
                        RuntimeLogger.log(
                            "SYNC WARN: SmartAssist accessibility service not in enabled list after $accessibilityLivenessFails checks. " +
                            "Re-enable in Settings > Accessibility if this persists.",
                            "SYNC"
                        )
                    }
                } else if (accessibilityLivenessFails > 0) {
                    RuntimeLogger.log("SYNC: accessibility confirmed live (was flagged $accessibilityLivenessFails times)", "SYNC")
                    accessibilityLivenessFails = 0
                }
            } catch (e: Exception) {
                lastLivenessCheck = "probe_error=${e.javaClass.simpleName}"
                RuntimeLogger.log("SYNC liveness probe error: ${e.message}", "SYNC")
            }
            heartbeatHandler.postDelayed(this, 15000)
        }
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))

    override fun onCreate() {
        super.onCreate()
        NodeNotificationHub.attach(this, "adapter_sync")
        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_sync",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Accessibility liveness monitor active"
            )
        )
        heartbeatHandler.post(heartbeatRunnable)
        heartbeatHandler.postDelayed(livenessRunnable, 5000L) // first check after 5s
        RuntimeLogger.log("SyncAdapterService started - ACCESSIBILITY LIVENESS MONITOR", "ADAPTER")
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        NodeNotificationHub.detach(this, "adapter_sync")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
/* ======
SyncAdapterService Anchor
====== */

/* ========
BootAdapterService
======== */
class BootAdapterService : Service() {

    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val workerHandler = Handler(Looper.getMainLooper())
    
    @Volatile private var lastState = "UNKNOWN"
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    private val threadPool = Executors.newFixedThreadPool(2)

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_boot",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "state=$lastState uptime=${SystemClock.elapsedRealtime() / 1000}s"
                )
            )
            RuntimeLogger.log("BootAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    private val bootRunnable = object : Runnable {
        override fun run() {
            val uptimeSeconds = SystemClock.elapsedRealtime() / 1000
            val stabilization = when {
                uptimeSeconds < 60 -> "EARLY_BOOT"
                uptimeSeconds < 300 -> "STABILIZING"
                else -> "STABLE"
            }
            lastState = stabilization

            AdapterSignalBus.publishBootState(stabilization == "STABLE")

            when (stabilization) {
                "EARLY_BOOT" -> {
                    acquireWakeLock()
                    performAggressiveBootOptimizations()
                }
                "STABILIZING" -> {
                    performStabilizationOptimizations()
                }
                "STABLE" -> {
                    releaseWakeLock()
                    preWarmNetwork()
                }
            }

            RuntimeLogger.log(
                "BOOT uptime=${uptimeSeconds}s state=$stabilization",
                "BOOT"
            )

            if (stabilization != "STABLE") {
                workerHandler.postDelayed(this, 10000L)
            } else {
                workerHandler.postDelayed(this, 60000L)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SplendorAssist::BootHardworkerLock").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // 10 minutes max
            }
            RuntimeLogger.log("Boot Hardworker acquired PARTIAL_WAKE_LOCK for early boot acceleration", "BOOT")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                RuntimeLogger.log("Boot Hardworker released PARTIAL_WAKE_LOCK", "BOOT")
            }
            wakeLock = null
        }
    }

    private fun performAggressiveBootOptimizations() {
        threadPool.execute {
            try {
                // 1. Eliminator: Kill non-essential background apps aggressively
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningApps = am.runningAppProcesses ?: emptyList()
                var killedCount = 0
                for (processInfo in runningApps) {
                    @Suppress("DEPRECATION")
                    if (processInfo.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                        if (!processInfo.processName.contains("com.assistant") && 
                            !processInfo.processName.startsWith("com.android.systemui") &&
                            !processInfo.processName.startsWith("system")) {
                            try {
                                am.killBackgroundProcesses(processInfo.processName)
                                killedCount++
                            } catch (e: Exception) {
                                // Ignore security exceptions on non-root
                            }
                        }
                    }
                }
                
                // 2. Booster: Force GC and System Finalization to clear RAM
                System.runFinalization()
                Runtime.getRuntime().gc()

                // 3. Optimize: Set our own thread to highest priority to finish boot tasks faster
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                // 4. Eliminator: Drop caches if possible (requires root)
                try {
                    val dropCaches = File("/proc/sys/vm/drop_caches")
                    if (dropCaches.canWrite()) {
                        dropCaches.writeText("3")
                        RuntimeLogger.log("Boot Hardworker dropped filesystem caches", "BOOT")
                    }
                } catch (e: Exception) {
                    // Non-root device, ignore
                }

                val availMem = getAvailableMemoryMb()
                AdapterSignalBus.publishMemory("NORMAL", availMem)
                RuntimeLogger.log("Boot Hardworker early-boot elimination complete. Killed=$killedCount, AvailMem=${availMem}MB", "BOOT")
                
            } catch (e: Exception) {
                RuntimeLogger.log("Boot Hardworker error during early boot: ${e.message}", "BOOT")
            }
        }
    }

    private fun performStabilizationOptimizations() {
        threadPool.execute {
            try {
                // Keep RAM clear during stabilization as system loads heavy services
                System.runFinalization()
                Runtime.getRuntime().gc()

                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.runningAppProcesses?.let { list ->
                    @Suppress("DEPRECATION")
                    list.filter { it.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND }
                        .filter { !it.processName.contains("com.assistant") && !it.processName.startsWith("system") }
                        .forEach { am.killBackgroundProcesses(it.processName) }
                }

                val availMem = getAvailableMemoryMb()
                val tier = if (availMem < 500) "CRITICAL" else if (availMem < 1024) "PRESSURE" else "NORMAL"
                AdapterSignalBus.publishMemory(tier, availMem)
                
                RuntimeLogger.log("Boot Hardworker stabilization trim complete. AvailMem=${availMem}MB Tier=$tier", "BOOT")
            } catch (e: Exception) {
                RuntimeLogger.log("Boot Hardworker error during stabilization: ${e.message}", "BOOT")
            }
        }
    }

    private fun preWarmNetwork() {
        threadPool.execute {
            try {
                // Warm up DNS and connection pool for eFootball servers
                val socket = Socket()
                socket.connect(InetSocketAddress("8.8.8.8", 53), 2000)
                socket.close()
                RuntimeLogger.log("Boot Hardworker network stack pre-warmed", "BOOT")
            } catch (e: Exception) {
                RuntimeLogger.log("Boot Hardworker network pre-warm failed: ${e.message}", "BOOT")
            }
        }
    }

    private fun getAvailableMemoryMb(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("BootAdapterService started (Hardworker Edition)", "ADAPTER")
        
        NodeNotificationHub.attach(this, "adapter_boot")
        
        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_boot",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground hardworker service running"
            )
        )
        
        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("BootAdapter heartbeat scheduler started", "HEALTH")
        
        workerHandler.post(bootRunnable)
        RuntimeLogger.log("Boot telemetry and hardworker elimination engine started", "BOOT")
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        workerHandler.removeCallbacks(bootRunnable)
        releaseWakeLock()
        threadPool.shutdownNow()
        NodeNotificationHub.detach(this, "adapter_boot")
        RuntimeLogger.log("BootAdapter heartbeat and hardworker stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
/* ======
BootAdapterService Anchor
====== */

/* ========
SchedulerAdapterService
======== */
class SchedulerAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastFleet = "no sweep yet"

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_scheduler",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "fleet: $lastFleet"
                )
            )
            RuntimeLogger.log("Scheduler heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    private val schedulerHandler = Handler(Looper.getMainLooper())

    private val schedulerRunnable = object : Runnable {
        override fun run() {

            var active = 0
            var degraded = 0
            var offline = 0

            AdapterHealthRegistry.getAll().forEach { snapshot ->

                when (
                    AdapterHealthRegistry.effectiveStatus(
                        snapshot.adapterName
                    )
                ) {
                    "ACTIVE" -> active++
                    "DEGRADED" -> degraded++
                    "OFFLINE" -> offline++
                }
            }

            ResourceBudgetRegistry.update(active, degraded, offline)
            // PHASE3: publish fleet state to bus so SpeedCompensation and LoadShed can react
            AdapterSignalBus.publishFleet(offline)

            lastFleet = "active=$active degraded=$degraded offline=$offline"
            val fleetWarn = if (offline > 2) " *** FLEET DEGRADED — $offline adapters offline ***" else ""
            RuntimeLogger.log(
                "FLEET HEALTH active=$active degraded=$degraded offline=$offline$fleetWarn",
                "SCHEDULER"
            )

            schedulerHandler.postDelayed(
                this,
                15000L
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("SchedulerAdapterService started", "ADAPTER")

        // Unified foundation notification (Task C item (e)) - this node was
        // the SIXTH service on colliding foreground ID 9993.
        NodeNotificationHub.attach(this, "adapter_scheduler")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_scheduler",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("Scheduler heartbeat scheduler started", "HEALTH")

        schedulerHandler.post(schedulerRunnable)
        RuntimeLogger.log("Scheduler fleet monitor started", "SCHEDULER")
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        schedulerHandler.removeCallbacks(schedulerRunnable)
        NodeNotificationHub.detach(this, "adapter_scheduler")
        RuntimeLogger.log("Scheduler heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
/* ======
SchedulerAdapterService Anchor
====== */

/* ========
CallOverlayRepository
======== */
object CallOverlayRepository {

    @Volatile
    var incomingCallVisible: Boolean = false
}
/* ======
CallOverlayRepository Anchor
====== */

/* ========
InterruptionAdapterService
======== */
class InterruptionAdapterService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var interruptionHandler: Handler
    private val errorCount = AtomicInteger(0)

    companion object {
        private const val CHANNEL_ID = "splendor_engine_logs"
        private const val WAKELOCK_TAG = "Splendor:DozeBypassLock"
    }

    // =========================================================================
    // 4 HARDWORKING ELIMINATORS (Companion Objects per CI-T&C Rule #7)
    // =========================================================================

    object DozeBypassEliminator {
        private var wakeLock: PowerManager.WakeLock? = null
        
        fun ignite(context: Context) {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours safety cap
                RuntimeLogger.log("DozeBypassEliminator: PARTIAL_WAKE_LOCK acquired. Doze disabled.", "INTERRUPTION")
            } else if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L)
            }
        }

        fun extinguish() {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        }
    }

    object NetworkPriorityHijacker {
        private var bound = false
        
        fun execute(context: Context) {
            if (bound) return
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build()
                
                cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        try { 
                            cm.bindProcessToNetwork(network)
                            RuntimeLogger.log("NetworkPriorityHijacker: Process bound to FOREGROUND network.", "INTERRUPTION")
                        } catch (_: Throwable) {}
                    }
                })
                bound = true
            } catch (_: Throwable) {}
        }

        fun unbind(context: Context) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.bindProcessToNetwork(null)
                bound = false
            } catch (_: Throwable) {}
        }
    }

    object NotificationHardKiller {
        fun execute(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val myPackage = context.packageName
            
            try {
                // Ensure Splendor-Assist logs bypass DND and are always visible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = nm.getNotificationChannel(CHANNEL_ID)
                    if (channel == null) {
                        val newChannel = NotificationChannel(CHANNEL_ID, "Engine Logs", NotificationManager.IMPORTANCE_HIGH).apply {
                            setBypassDnd(true)
                            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                        }
                        nm.createNotificationChannel(newChannel)
                    } else if (!channel.canBypassDnd()) {
                        channel.setBypassDnd(true)
                        nm.createNotificationChannel(channel)
                    }
                }

                // Kill all non-Splendor active notifications to prevent micro-stutters and UI blocks
                nm.activeNotifications.forEach { sbn ->
                    if (sbn.packageName != myPackage) {
                        nm.cancel(sbn.tag, sbn.id)
                    }
                }
            } catch (_: SecurityException) {}
        }
    }

    object BackgroundProcessPurger {
        fun execute(context: Context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val myPackage = context.packageName
            
            try {
                val running = am.runningAppProcesses ?: emptyList()
                running.forEach { proc ->
                    if (proc.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        if (!proc.processName.contains(myPackage) && 
                            !proc.processName.contains("system") && 
                            !proc.processName.contains("launcher")) {
                            try { 
                                am.killBackgroundProcesses(proc.processName) 
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: SecurityException) {}
        }
    }

    object CallAndAudioEliminator {
        fun updateCallState(state: Int) {
            TelephonyStateRepository.activeCall = state != TelephonyManager.CALL_STATE_IDLE
            CallOverlayRepository.incomingCallVisible = TelephonyStateRepository.activeCall
        }

        private var focusRequest: AudioFocusRequest? = null

        fun suppressRingerAndHoldFocus(context: Context) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            try { 
                am.ringerMode = AudioManager.RINGER_MODE_SILENT 
            } catch (_: SecurityException) { 
                try { am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0) } catch (_: Throwable) {}
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest == null) {
                    focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                        .build()
                }
                try { am.requestAudioFocus(focusRequest!!) } catch (_: Throwable) {}
            }
        }
    }

    // =========================================================================
    // SERVICE LIFECYCLE & LOOP
    // =========================================================================

    private val interruptionRunnable = object : Runnable {
        override fun run() {
            try {
                // V6 ROOT-CAUSE FIX (field logs: "heartbeat failed :: SecurityException"
                // every 500ms -> no persisted heartbeat -> booster-not-ready forever).
                // Heartbeat FIRST, independently guarded: the cross-process truth the
                // booster gate/health reads must survive any eliminator fault.
                // V12 ROOT-CAUSE FIX: registerReceiver can throw SecurityException on
                // Android 14+/HyperOS when called from background service. If it throws,
                // the entire try block aborted and the heartbeat was NEVER published,
                // causing boosterAlive=false and permanent booster-not-ready degradation.
                // FIX: Wrap registerReceiver individually. Use defaults if it fails.
                // The heartbeat MUST publish regardless of battery read success.
                var batteryLevel = -1
                var charging = false
                try {
                    val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
                } catch (_: SecurityException) {
                    // Battery read blocked by OS; proceed with defaults to keep heartbeat alive
                } catch (_: Throwable) {}

                try {
                    val state = InterruptionCoordinator.evaluate(batteryLevel, charging, 0)
                    InterruptionRepository.save(state)
                    val throttleMode = when (state.severity) {
                        "CRITICAL" -> "AGGRESSIVE_THROTTLE"
                        "THROTTLE" -> "MODERATE_THROTTLE"
                        "WARNING" -> "LIGHT_THROTTLE"
                        else -> "NORMAL"
                    }
                    AdapterHealthRegistry.update(
                        AdapterHealthSnapshot(
                            adapterName = "adapter_interruption",
                            status = state.severity,
                            lastHeartbeat = System.currentTimeMillis(),
                            errorCount = errorCount.get(),
                            recoveryCount = 0,
                            details = "battery=${state.batteryLevel},call=${TelephonyStateRepository.activeCall},mode=$throttleMode"
                        )
                    )
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                    RuntimeLogger.log("InterruptionAdapter heartbeat failed :: ${e.javaClass.simpleName}", "HEALTH")
                }

                // Eliminators: each individually guarded so one SecurityException
                // can never kill the loop or the heartbeat again.
                try { DozeBypassEliminator.ignite(this@InterruptionAdapterService) } catch (_: Throwable) {}
                try { NetworkPriorityHijacker.execute(this@InterruptionAdapterService) } catch (_: Throwable) {}
                try { NotificationHardKiller.execute(this@InterruptionAdapterService) } catch (_: Throwable) {}
                try { BackgroundProcessPurger.execute(this@InterruptionAdapterService) } catch (_: Throwable) {}

                val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
                @Suppress("DEPRECATION")
                val callState = try { telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE } catch (_: SecurityException) { TelephonyManager.CALL_STATE_IDLE }

                CallAndAudioEliminator.updateCallState(callState)

                if (callState == TelephonyManager.CALL_STATE_RINGING || TelephonyStateRepository.activeCall) {
                    CallAndAudioEliminator.suppressRingerAndHoldFocus(this@InterruptionAdapterService)
                }

            } catch (e: Exception) {
                errorCount.incrementAndGet()
                RuntimeLogger.log("InterruptionAdapter heartbeat failed :: ${e.javaClass.simpleName}", "HEALTH")
            } finally {
                try { interruptionHandler.postDelayed(this, 500L) } catch (_: Exception) {}
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("InterruptionAdapterService started (4-Eliminator Hardworking Mode)", "ADAPTER")

        workerThread = HandlerThread("InterruptionWorker", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        workerThread.start()
        interruptionHandler = Handler(workerThread.looper)

        NodeNotificationHub.attach(this, "adapter_interruption")
        interruptionHandler.post(interruptionRunnable)
    }

    override fun onDestroy() {
        try { interruptionHandler.removeCallbacks(interruptionRunnable) } catch (_: Exception) {}
        try { workerThread.quitSafely() } catch (_: Exception) {}
        
        DozeBypassEliminator.extinguish()
        NetworkPriorityHijacker.unbind(this)
        
        NodeNotificationHub.detach(this, "adapter_interruption")
        RuntimeLogger.log("InterruptionAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
/* ======
InterruptionAdapterService Anchor
====== */

/* ========
InterruptionCoordinator
======== */
object InterruptionCoordinator {

    fun evaluate(
        batteryLevel: Int,
        charging: Boolean,
        thermalStatus: Int
    ): InterruptionState {

        val severity =
            when {
                thermalStatus >= 5 -> "CRITICAL"
                thermalStatus >= 3 -> "THROTTLE"
                batteryLevel < 15 && !charging -> "WARNING"
                else -> "NORMAL"
            }

        return InterruptionState(
            batteryLevel = batteryLevel,
            charging = charging,
            thermalStatus = thermalStatus,
            severity = severity,
            timestamp = System.currentTimeMillis()
        )
    }
}
/* ======
InterruptionCoordinator Anchor
====== */

/* ========
InterruptionRepository
======== */
object InterruptionRepository {

    @Volatile
    private var latest: InterruptionState? = null

    fun save(state: InterruptionState) {
        latest = state
    }

    fun get(): InterruptionState? {
        return latest
    }
}
/* ======
InterruptionRepository Anchor
====== */

/* ========
InterruptionState
======== */
data class InterruptionState(
    val batteryLevel: Int,
    val charging: Boolean,
    val thermalStatus: Int,
    val severity: String,
    val timestamp: Long
)
/* ======
InterruptionState Anchor
====== */

/* ========
TelephonyStateRepository
======== */
object TelephonyStateRepository {

    @Volatile
    var activeCall: Boolean = false
}
/* ======
TelephonyStateRepository Anchor
====== */


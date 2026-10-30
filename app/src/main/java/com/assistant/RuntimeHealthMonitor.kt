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

object RuntimeHealthMonitor {

    private const val FRAME_STALE_MS = 3000L
    private const val DECISION_STALE_MS = 3000L
    private const val EXECUTION_STALE_MS = 5000L

    private val evaluations = AtomicLong(0L)

    @Volatile
    private var lastEvaluatedMs: Long = 0L

    // frame-counter progress tracking between evaluations
    @Volatile private var prevFrames = -1L
    @Volatile private var framesProgressMs = 0L

    data class HealthState(
        val accessibilityAlive: Boolean,
        val overlayAlive: Boolean,
        val frameAlive: Boolean,
        val decisionAlive: Boolean,
        val busAlive: Boolean,
        val dispatchAlive: Boolean,
        val gameplayAlive: Boolean,
        val boosterAlive: Boolean,
        val degradedReasons: List<String>,
        val lastEvaluatedMs: Long
    )

    fun snapshot(): HealthState {
        evaluations.incrementAndGet()
        val now = System.currentTimeMillis()
        lastEvaluatedMs = now

        val runtime = RuntimeCoordinator.runtimeState()
        val frame = FrameAssembler.frameRuntimeSnapshot()
        val decision = RuntimeDecisionLoop.decisionRuntimeSnapshot()
        val contributions =
            com.assistant.execution.ContributionRegistry.contributionRuntimeSnapshot()
        val execution = GestureExecutionAuthority.executionRuntimeSnapshot()
        val registry = GameplayEngineRegistry.registryRuntimeSnapshot()

        val accessibilityAlive =
            runtime["accessibilityReady"] as? Boolean ?: false

        val overlayAlive =
            runtime["captureReady"] as? Boolean ?: false

        // ---- frame flow: alive while the frame counter keeps advancing ----
        val frames = (frame["frames"] as? Number)?.toLong() ?: 0L
        if (frames != prevFrames) {
            prevFrames = frames
            framesProgressMs = now
        }
        val frameFlowing = frames > 0L && now - framesProgressMs <= FRAME_STALE_MS
        val frameAlive = frame["state"] != "cold" && frameFlowing

        // ---- decision loop: judged against the frame flow it follows ----
        val decisions = (decision["decisions"] as? Number)?.toLong() ?: 0L
        val decisionLast = (decision["lastUpdatedMs"] as? Number)?.toLong() ?: 0L
        val decisionFresh = decisionLast > 0L && now - decisionLast <= DECISION_STALE_MS
        val decisionAlive = when {
            decisions <= 0L -> false
            decisionFresh -> true
            // frame flow paused: the loop is idle by design, not stalled;
            // frame-stale carries the real cause
            !frameFlowing -> true
            // frames flowing but the loop is not keeping pace: genuine stall
            else -> false
        }

        val busAlive =
            (runtime["busEnabled"] as? Boolean ?: false) ||
                ((contributions["offered"] as? Number)?.toLong() ?: 0L) > 0L

        // ---- dispatch: idle-with-nothing-requested is healthy ----
        val requested = (execution["requested"] as? Number)?.toLong() ?: 0L
        val acceptedCount = (execution["accepted"] as? Number)?.toLong() ?: 0L
        val executionLast = (execution["lastUpdatedMs"] as? Number)?.toLong() ?: 0L
        val dispatchAlive = when {
            requested == 0L -> true
            acceptedCount > 0L -> true
            executionLast > 0L && now - executionLast <= EXECUTION_STALE_MS -> true
            else -> false
        }

        val gameplayAlive =
            ((registry["engines"] as? Number)?.toInt() ?: 0) > 0 &&
                (
                    decisions > 0L ||
                    ((contributions["offered"] as? Number)?.toLong() ?: 0L) > 0L
                )

        // ---- booster: cross-process heartbeats, fresh ones only ----
        val boosterAlive = try { com.assistant.BoosterIgnition.isFleetReady() } catch (_: Throwable) { false }

        val degraded = mutableListOf<String>()

        if (!accessibilityAlive) degraded += "accessibility-not-ready"
        if (!overlayAlive) degraded += "capture-not-ready"
        if (!frameAlive) degraded += "frame-stale"
        if (!decisionAlive) degraded += "decision-stale"
        if (!busAlive) degraded += "bus-idle"
        if (!dispatchAlive) degraded += "dispatch-stale"
        if (!gameplayAlive) degraded += "gameplay-idle"
        if (!boosterAlive) degraded += "booster-not-ready"

        return HealthState(
            accessibilityAlive = accessibilityAlive,
            overlayAlive = overlayAlive,
            frameAlive = frameAlive,
            decisionAlive = decisionAlive,
            busAlive = busAlive,
            dispatchAlive = dispatchAlive,
            gameplayAlive = gameplayAlive,
            boosterAlive = boosterAlive,
            degradedReasons = degraded,
            lastEvaluatedMs = lastEvaluatedMs
        )
    }

    fun runtimeHealthSnapshot(): Map<String, Any> {
        val state = snapshot()
        return mapOf(
            "accessibilityAlive" to state.accessibilityAlive,
            "overlayAlive" to state.overlayAlive,
            "frameAlive" to state.frameAlive,
            "decisionAlive" to state.decisionAlive,
            "busAlive" to state.busAlive,
            "dispatchAlive" to state.dispatchAlive,
            "gameplayAlive" to state.gameplayAlive,
            "boosterAlive" to state.boosterAlive,
            "degradedReasons" to state.degradedReasons.joinToString(","),
            "lastEvaluatedMs" to state.lastEvaluatedMs,
            "evaluations" to evaluations.get()
        )
    }

    fun reset() {
        evaluations.set(0L)
        lastEvaluatedMs = 0L
        prevFrames = -1L
        framesProgressMs = 0L
    }
}

/* ========
RuntimePerformanceCoordinator
======== */

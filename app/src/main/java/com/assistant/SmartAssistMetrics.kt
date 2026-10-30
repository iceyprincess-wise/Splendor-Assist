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

object SmartAssistMetrics {
    data class GoalkeeperShadowDiagnostics(
        val observations: Long,
        val lastPhase: Int,
        val lastStartX: Float,
        val lastStartY: Float,
        val lastEndX: Float,
        val lastEndY: Float,
        val lastDuration: Long,
        val lastReason: String,
        val lastUpdatedMs: Long
    )

    private var goalkeeperShadowObservations: Long = 0L
    private var goalkeeperShadowLastPhase: Int = 0
    private var goalkeeperShadowLastStartX: Float = 0f
    private var goalkeeperShadowLastStartY: Float = 0f
    private var goalkeeperShadowLastEndX: Float = 0f
    private var goalkeeperShadowLastEndY: Float = 0f
    private var goalkeeperShadowLastDuration: Long = 0L
    private var goalkeeperShadowLastReason: String = "no goalkeeper shadow observation yet"
    private var goalkeeperShadowLastUpdatedMs: Long = 0L

    @Synchronized
    fun recordGoalkeeperShadow(request: ExecutionRequest, reason: String) {
        goalkeeperShadowObservations += 1L
        goalkeeperShadowLastPhase = request.phase
        goalkeeperShadowLastStartX = request.startX
        goalkeeperShadowLastStartY = request.startY
        goalkeeperShadowLastEndX = request.endX
        goalkeeperShadowLastEndY = request.endY
        goalkeeperShadowLastDuration = request.duration
        goalkeeperShadowLastReason = reason
        goalkeeperShadowLastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun goalkeeperShadowRuntimeSnapshot(): Map<String, Any> =
        mapOf(
            "observations" to goalkeeperShadowObservations,
            "lastPhase" to goalkeeperShadowLastPhase,
            "lastStartX" to goalkeeperShadowLastStartX,
            "lastStartY" to goalkeeperShadowLastStartY,
            "lastEndX" to goalkeeperShadowLastEndX,
            "lastEndY" to goalkeeperShadowLastEndY,
            "lastDuration" to goalkeeperShadowLastDuration,
            "lastReason" to goalkeeperShadowLastReason,
            "lastUpdatedMs" to goalkeeperShadowLastUpdatedMs
        )

    data class BusExecutionDiagnostics(
        val consumed: Long,
        val dispatched: Long,
        val failed: Long,
        val lastSource: String,
        val lastPhase: Int,
        val lastDuration: Long,
        val lastStartX: Float,
        val lastStartY: Float,
        val lastEndX: Float,
        val lastEndY: Float,
        val lastReason: String,
        val lastUpdatedMs: Long
    )

    private var busConsumed: Long = 0L
    private var busDispatched: Long = 0L
    private var busFailed: Long = 0L
    private var busLastSource: String = "none"
    private var busLastPhase: Int = 0
    private var busLastDuration: Long = 0L
    private var busLastStartX: Float = 0f
    private var busLastStartY: Float = 0f
    private var busLastEndX: Float = 0f
    private var busLastEndY: Float = 0f
    private var busLastReason: String = "no bus request consumed yet"
    private var busLastUpdatedMs: Long = 0L

    @Synchronized
    fun recordBusConsumed(request: ExecutionRequest) {
        busConsumed += 1L
        busLastSource = request.source.name
        busLastPhase = request.phase
        busLastDuration = request.duration
        busLastStartX = request.startX
        busLastStartY = request.startY
        busLastEndX = request.endX
        busLastEndY = request.endY
        busLastReason = "CentralExecutionBus request consumed by accessibility engine"
        busLastUpdatedMs = System.currentTimeMillis()
        submitRequest()
    }

    @Synchronized
    fun recordBusDispatchResult(request: ExecutionRequest, dispatched: Boolean) {
        if (dispatched) {
            busDispatched += 1L
            busLastReason = "Gesture dispatched from consumed bus request"
        } else {
            busFailed += 1L
            busLastReason = "Gesture dispatch failed from consumed bus request"
        }
        busLastSource = request.source.name
        busLastPhase = request.phase
        busLastDuration = request.duration
        busLastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun busExecutionRuntimeSnapshot(): Map<String, Any> =
        mapOf(
            "consumed" to busConsumed,
            "dispatched" to busDispatched,
            "failed" to busFailed,
            "lastSource" to busLastSource,
            "lastPhase" to busLastPhase,
            "lastDuration" to busLastDuration,
            "lastStartX" to busLastStartX,
            "lastStartY" to busLastStartY,
            "lastEndX" to busLastEndX,
            "lastEndY" to busLastEndY,
            "lastReason" to busLastReason,
            "lastUpdatedMs" to busLastUpdatedMs
        )

    fun gameplayDownstreamRuntimeSnapshot(): Map<String, Any> {
        val event = GameplayDecisionEngine.gameplayDownstreamSnapshot()
        return mapOf(
            "sequence" to (event?.sequence ?: 0L),
            "source" to (event?.source ?: "none"),
            "amplification" to (event?.amplification ?: 1000000.0f),
            "active" to (event != null)
        )
    }

    fun gameplayAmplificationRuntimeSnapshot(): Map<String, Number> {
        val snapshot = GameplayDecisionEngine.gameplayAmplificationSnapshot()
        return mapOf(
            "amplification" to 1000000.0f,
            "decisionCycles" to snapshot.first,
            "lastAuthority" to snapshot.second
        )
    }

    val requestsSubmitted = AtomicLong()
    val requestsExecuted = AtomicLong()
    val trajectoryProduced = AtomicLong()

    fun submitRequest() {
        requestsSubmitted.incrementAndGet()
    }

    fun executeRequest() {
        requestsExecuted.incrementAndGet()
    }

    fun produceTrajectory() {
        trajectoryProduced.incrementAndGet()
    }

    fun snapshot(): String {
        return buildString {
            append("Submitted : ")
            append(requestsSubmitted.get())
            append("\n")
            append("Executed : ")
            append(requestsExecuted.get())
            append("\n")
            append("Trajectory : ")
            append(trajectoryProduced.get())
        }
    }

    fun reset() {
        requestsSubmitted.set(0L)
        requestsExecuted.set(0L)
        trajectoryProduced.set(0L)
    }

    fun magneticFeetRuntimeSnapshot(): Map<String, Any> {
        val state = MagneticFeetEngine.magneticFeetSnapshot()
        return mapOf(
            "sequence" to (state?.sequence ?: 0L),
            "amplification" to (state?.amplification ?: 1.0f),
            "touchRetention" to (state?.result?.touchRetention ?: 0.0f),
            "interceptionResistance" to (state?.result?.interceptionResistance ?: 0.0f),
            "possessionControl" to (state?.result?.possessionControl ?: 0.0f)
        )
    }

    fun crossingLaneRuntimeSnapshot(): Map<String, Any> {
        val state = CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot()
        val lanes = state?.result?.lanes.orEmpty()
        return mapOf(
            "sequence" to (state?.sequence ?: 0L),
            "amplification" to (state?.amplification ?: 1.0f),
            "laneCount" to lanes.size,
            "viableLaneCount" to lanes.count { it.viable },
            "bestConfidence" to (lanes.maxOfOrNull { it.confidence } ?: 0.0f)
        )
    }

    fun magneticFeetActivationRuntimeSnapshot(): Map<String, Any> {
        val state = MagneticFeetEngine.magneticFeetActivationDiagnostics()
        return mapOf(
            "calls" to state.calls,
            "lastPressure" to state.lastPressure,
            "lastStrength" to state.lastStrength,
            "lastReason" to state.lastReason,
            "lastUpdatedMs" to state.lastUpdatedMs
        )
    }

    fun gameplayActivationRuntimeSnapshot(): Map<String, Any> {
        val state = GameplayDecisionEngine.gameplayActivationDiagnostics()
        return mapOf(
            "adaptiveModeCalls" to state.adaptiveModeCalls,
            "decideCalls" to state.decideCalls,
            "lastHasBall" to state.lastHasBall,
            "lastMode" to state.lastMode,
            "lastStrength" to state.lastStrength,
            "lastReason" to state.lastReason,
            "lastUpdatedMs" to state.lastUpdatedMs
        )
    }

    fun controllerEntryRuntimeSnapshot(): Map<String, Any> {
        val state = ActiveGestureControllerDiagnostics.snapshot()
        return mapOf(
            "entryCalls" to state.entryCalls,
            "blockedCalls" to state.blockedCalls,
            "lastReason" to state.lastReason,
            "lastStartX" to state.lastStartX,
            "lastStartY" to state.lastStartY,
            "lastEndX" to state.lastEndX,
            "lastEndY" to state.lastEndY,
            "lastDuration" to state.lastDuration,
            "lastUpdatedMs" to state.lastUpdatedMs
        )
    }

    private var gameplayHeartbeatTicks: Long = 0L
    private var gameplayHeartbeatLastReason: String = "not started"
    private var gameplayHeartbeatLastUpdatedMs: Long = 0L
    @Volatile private var gameplayHeartbeatLastMode: Int = -1
    @Volatile private var gameplayHeartbeatLastAuthority: Float = 0f

    @Synchronized
    fun runGameplayHeartbeat(reason: String = "diagnosis heartbeat") {
        gameplayHeartbeatTicks += 1L
        gameplayHeartbeatLastReason = reason
        gameplayHeartbeatLastUpdatedMs = System.currentTimeMillis()

        val crossing = CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot()
        val lanes = crossing?.result?.lanes.orEmpty()
        val laneCount = lanes.size
        // SPLENDOR_HEARTBEAT_LANE_AUTHORITY_V1 - the previous body fed the
        // same max confidence into shot/pass/cross, so the three scores could
        // never diverge and the selected mode was constant. The crossing
        // lanes carry a target per lane; group their confidence by goal-mouth
        // side so the authority inputs are distinct measurements.
        val leftLanes = lanes.filter { it.targetX < 0.5f }
        val rightLanes = lanes.filter { it.targetX >= 0.5f }
        val centralLanes = lanes.filter { it.targetX >= 0.35f && it.targetX < 0.65f }
        val overallConfidence = lanes.maxOfOrNull { it.confidence } ?: 0.0f
        val shotAuthority =
            (centralLanes.maxOfOrNull { it.confidence } ?: 0.0f).coerceIn(0.0f, 1.0f)
        val passAuthority =
            (leftLanes.maxOfOrNull { it.confidence } ?: 0.0f).coerceIn(0.0f, 1.0f)
        val crossAuthority =
            (rightLanes.maxOfOrNull { it.confidence } ?: 0.0f).coerceIn(0.0f, 1.0f)
        val temporal = TemporalMemoryState(
            temporalConfidence = overallConfidence.coerceIn(0.0f, 1.0f),
            exponentialMovingAverage = overallConfidence.coerceIn(0.0f, 1.0f),
            rollingMean = overallConfidence.coerceIn(0.0f, 1.0f),
            historyStability = 1.0f,
            confidenceVariance = 0.0f,
            confidenceTrend = 0.0f
        )

        val adaptiveModeAuthority =
            GameplayDecisionEngine.selectVisionAdaptiveMode(
                hasBall = laneCount > 0,
                shotAuthority = shotAuthority,
                passAuthority = passAuthority,
                crossAuthority = crossAuthority,
                visionConfidence = overallConfidence.coerceIn(0.0f, 1.0f),
                tacticalConfidence = overallConfidence.coerceIn(0.0f, 1.0f),
                intelligenceConfidence = overallConfidence.coerceIn(0.0f, 1.0f),
                runtimeCalibration = 1.0f,
                onlineAdaptation = 1.0f,
                temporal = temporal
            )
        // Consume the result instead of discarding it: publish the selected
        // mode and the winning score so the heartbeat is not a pure no-op.
        gameplayHeartbeatLastMode = adaptiveModeAuthority.mode
        gameplayHeartbeatLastAuthority = maxOf(
            adaptiveModeAuthority.shotScore,
            adaptiveModeAuthority.passScore,
            adaptiveModeAuthority.crossScore
        )
    }

    fun gameplayHeartbeatRuntimeSnapshot(): Map<String, Any> =
        mapOf(
            "ticks" to gameplayHeartbeatTicks,
            "lastReason" to gameplayHeartbeatLastReason,
            "lastUpdatedMs" to gameplayHeartbeatLastUpdatedMs,
            "lastMode" to gameplayHeartbeatLastMode,
            "lastAuthority" to gameplayHeartbeatLastAuthority
        )

    // ==== Decision mirror ====
    // Written by com.assistant.overlay.metrics.SmartAssistMetrics so that
    // decision-level counters and request-level counters share one reader.
    private val decisionsMade = AtomicLong()
    private val decisionActions = AtomicLong()
    private val decisionErrors = AtomicLong()
    private val decisionTimeTotal = AtomicLong()
    @Volatile private var lastDecisionMs: Long = 0L
    @Volatile private var lastDecisionUpdatedMs: Long = 0L

    fun decisionRuntimeSnapshot(): Map<String, Any> {
        val made = decisionsMade.get()
        return mapOf(
            "decisionsMade" to made,
            "actionsTriggered" to decisionActions.get(),
            "errorCount" to decisionErrors.get(),
            "avgDecisionTimeMs" to if (made > 0L) decisionTimeTotal.get() / made else 0L,
            "lastDecisionTimeMs" to lastDecisionMs,
            "lastUpdatedMs" to lastDecisionUpdatedMs
        )
    }

}

/* ========
TacticalBehaviorRecognitionResult
======== */

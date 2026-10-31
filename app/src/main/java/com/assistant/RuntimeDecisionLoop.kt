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

object RuntimeDecisionLoop {

    private const val MAX_OPTICAL_VARIANCE_FOR_ACTION = 0.85f
    private val decisions = AtomicLong(0L)
    private val routed = AtomicLong(0L)
    private val idleNoContribution = AtomicLong(0L)
    private val idleUntrusted = AtomicLong(0L)

    @Volatile private var lastAction: String = "none"
    @Volatile private var lastWeight: Float = 0f
    @Volatile private var lastUpdatedMs: Long = 0L

    private fun classScale(actionClass: ActionClass, hasTactical: Boolean = false): Float =
        when (actionClass) {
            ActionClass.MOVE -> if (hasTactical) 0.35f else 0.85f
            ActionClass.NONE -> 0f
            else -> 1f
        }

    /* Called once per assembled frame by OverlayService's capture loop. */
    fun onFrame(frame: RuntimeFrame): Boolean {
        decisions.incrementAndGet()
        lastUpdatedMs = System.currentTimeMillis()

        if (!com.assistant.VisionTrust.isGameForeground() || !com.assistant.SmartAssistRepository.enabled()) {
            idleUntrusted.incrementAndGet()
            lastAction = "idle-untrusted"
            return false
        }

        if (!frame.trusted || frame.opticalVariance >= MAX_OPTICAL_VARIANCE_FOR_ACTION) {
            idleUntrusted.incrementAndGet()
            lastAction = "idle-untrusted"
            return false
        }

        // CONTROL-MAPPING GATE: the mapping/settings screen must never
        // receive gameplay gestures; trained in-match cluster arms the stack.
        // REMOVED SETTINGS GATE: Button visibility/match MUST NOT be the activation gate for the full gameplay engine.
        // Gameplay engines must run unconditionally to ensure 0.00ms reaction to live match states.
        ControlMappingTrainer.observeOutcome(frame)

        // SPLENDOR_AUTHORITY_REACHES_ARBITRATION_V1 - the master authority
        // slider used to reach RuntimeObservation only, so it could not change
        // what the engine actually did. Scale the weight every arbiter reads
        // (weight = authority * confidence) by the operator authority. At the
        // 100% default the factor is 1.0, so default behaviour is unchanged.
        val authorityFactor =
            RuntimePerformanceCoordinator.authority().coerceIn(0, 100) / 100f
        val collected = GameplayEngineRegistry.collect(frame)
        val contributions = if (authorityFactor >= 1f) collected else collected.map { c ->
            c.copy(authority = (c.authority * authorityFactor).coerceIn(0f, 1f))
        }
        val netHold = AdapterSignalBus.netIsHold
        
        // ConAI Meta-Arbiter decision loop
        val best = ConAIEngine.arbitrate(frame, contributions, netHold)

        val emergency = ContributionRegistry.drainBest()

        val request = chooseRequest(frame, best, emergency)
        if (request == null) {
            idleNoContribution.incrementAndGet()

            lastAction = "idle-no-contribution"

            return false
        }

        // ── Crowding Zone detection ─────────────────────────────────
        // Runs BEFORE amplifiers. Arms the duration cap and updates
        // AdapterSignalBus.crowdingZone for LagVerdictEngine next poll.
        val inCrowdedZone = CrowdingZoneDetector.evaluate(frame)

        // ── Fighting Spirit amplification ─────────────────────────────────
        // Evaluate AFTER arbitration so it amplifies the already-chosen
        // winner rather than competing in arbitration itself.
        val fs = FightingSpiritEngine.evaluate(frame)
        val finalRequest = if (fs.active) {
            request.copy(
                duration = (request.duration + fs.durationBoostMs)
                    .coerceIn(15L, 85L)
            )
        } else request
        // Apply authority boost and accuracy retention to the winning contribution's weight
        if (fs.active && best != null) {
            lastWeight = (best.weight * fs.authorityBoost * fs.accuracyRetention).coerceIn(0f, 1f)
        }

        // ── Captaincy Skill amplification ────────────────────────────────
        // Captaincy is a passive, continuous team-wide fatigue reduction.
        // Applied after Fighting Spirit so both effects compound correctly.
        val captaincy = CaptaincySkillEngine.evaluate(frame)
        // teamLiftFactor: multiplicative duration scale — real terminal effect.
        // composureBoostMs: additive on top — both reach HybridExecutionTerminal.
        val resolvedRequest = if (captaincy.active) {
            val liftedDuration = (finalRequest.duration * captaincy.teamLiftFactor).toLong()
            finalRequest.copy(
                duration = (liftedDuration + captaincy.composureBoostMs)
                    .coerceIn(15L, 85L)
            )
        } else finalRequest

        // ── Crowding Zone saturation cap ──────────────────────────────
        // Penalty box / corner: both amplifiers peak simultaneously.
        // Cap at 45ms so inputs stay deliberate, not desperate spam.
        val terminalRequest = if (inCrowdedZone && resolvedRequest.duration > 45L) {
            resolvedRequest.copy(duration = resolvedRequest.duration.coerceAtMost(45L))
        } else resolvedRequest

        val accepted = HybridExecutionTerminal.route(terminalRequest)
        if (accepted) {
            routed.incrementAndGet()
            ControlMappingTrainer.recordDispatch(terminalRequest.phase, terminalRequest.duration)
            ActionOutcomeVerifier.recordDispatch(terminalRequest, frame)
            try {
                com.assistant.events.GameplayEventHub.emit(
                    "routed",
                    "source=${request.source} phase=${request.phase}"
                )
            } catch (_: Throwable) {
            }
        }
        ActionOutcomeVerifier.verify(frame)
        lastAction = describe(best, emergency)
        lastWeight = best?.weight ?: 0f
        return accepted
    }

    @Suppress("UNUSED_PARAMETER")
    private fun chooseRequest(
        _frame: RuntimeFrame,  // reserved — frame context available for future coord use
        best: EngineContribution?,
        emergency: ExecutionRequest?
    ): ExecutionRequest? {
        // Emergency (goalkeeper etc.) outranks a normal contribution.
        if (emergency != null) {
            if (best == null) return emergency
            val emergencyPriority = HybridExecutionTerminal.priority(emergency.source)
            val normalPriority = HybridExecutionTerminal.priority(ExecutionSource.SMART_ASSIST)
            if (emergencyPriority > normalPriority) return emergency
        }
        if (best == null) return null

        val pred = BallTrajectoryPredictor.current()
        val leadX = if (pred.speed > 20f) pred.predictedX else best.targetX
        val leadY = if (pred.speed > 20f) pred.predictedY else best.targetY
        return ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = best.actionClass.ordinal,
            // SPLENDOR_CONTROL_MAP_V1 - derive the joystick origin from the
            // trainer's live normalised control map instead of a fixed point.
            // All request coordinates live in the capture frame's space, so the
            // normalised centre is scaled by the capture dimensions.
            startX = com.assistant.vision.CameraProfile.captureWidthOrFallback() *
                ControlMappingTrainer.joystickCenterX(),
            startY = com.assistant.vision.CameraProfile.captureHeightOrFallback() *
                ControlMappingTrainer.joystickCenterY(),
            endX = leadX.coerceAtLeast(0f),
            endY = leadY.coerceAtLeast(0f),
            duration = (ControlMappingTrainer.personalDuration(best.actionClass, best.durationHintMs) * executionBias(best.actionClass)).toLong().coerceIn(15L, 85L)
        )
    }

    /* f5: Pass/Shot/Cross threshold slider -> live duration factor. 50 = 1.0 (unchanged),
       0 = 0.0, 100 = 2.0. DEFEND/EVADE/other classes are not biased. */
    private fun executionBias(actionClass: ActionClass): Float {
        val cfg = SmartAssistRepository.configuration()
        val threshold = when (actionClass) {
            ActionClass.PASS -> cfg.passThreshold
            ActionClass.SHOT -> cfg.shotThreshold
            ActionClass.CROSS -> cfg.crossThreshold
            else -> 50
        }
        return threshold.coerceIn(0, 100) / 50f
    }

    private fun describe(best: EngineContribution?, emergency: ExecutionRequest?): String =
        when {
            emergency != null && best == null -> "emergency:${emergency.source}"
            best != null -> "${best.engine}:${best.actionClass}"
            else -> "none"
        }

    fun reset() {
        decisions.set(0L); routed.set(0L)
        FightingSpiritEngine.reset()
        CaptaincySkillEngine.reset()
        CrowdingZoneDetector.reset()
        idleNoContribution.set(0L); idleUntrusted.set(0L)
        lastAction = "none"; lastWeight = 0f; lastUpdatedMs = 0L
    }

    fun decisionRuntimeSnapshot(): Map<String, Any> = mapOf(
        "decisions" to decisions.get(),
        "routed" to routed.get(),
        "idleUntrusted" to idleUntrusted.get(),
        "idleNoContribution" to idleNoContribution.get(),
        "lastAction" to lastAction,
        "lastWeight" to lastWeight,
        "lastUpdatedMs" to lastUpdatedMs,
        "mappingUiMode" to ControlMappingTrainer.uiMode.name,
        "mappingMatchActive" to ControlMappingTrainer.matchActive,
        "mappingTrainedSlots" to ControlMappingTrainer.trainedSlots()
    )
}

/* ========
RuntimeObservation
======== */
/**
 * Immutable runtime observation consumed by InAppAgentCore.
 *
 * This is deliberately a snapshot:
 * gameplay engines remain owners of gameplay state;
 * the agent only observes authoritative runtime surfaces.
 */

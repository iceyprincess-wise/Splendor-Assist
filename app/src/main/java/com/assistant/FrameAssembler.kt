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

internal const val GAMEPLAY_DECISION_ENGINE_RAPID_EXECUTION_TAG = "GameplayDecisionEngine.rapidPrime"

internal const val GAMEPLAY_DECISION_ENGINE_PRIME_AUTHORITY_TAG = "GameplayDecisionEngine.prime"

internal const val GAMEPLAY_ENGINE_AMPLIFICATION: Float = 1000000.0f

internal const val MAX_TELEMETRY_AGE_MS = 250L

internal const val SERVER_TICK_RATE_MS = 33.33333f // 30Hz Authoritative Tick Window

internal const val DISPLAY_REFRESH_120HZ_MS = 8.333333f

internal const val ADHESION_COEFFICIENT = 1.25f       // Magnetic retention pull factor

internal const val DRIFT_FILTER_BETA = 0.90f          // Jitter filter strength

internal const val MIN_PULSE_INTERVAL_NS = 7_000_000L // ~142Hz micro-tick limit (7ms)

internal const val OMEGA_TURNING_THRESHOLD = 6.0f     // Joystick displacement speed threshold


object FrameAssembler {

    private val frameCounter = AtomicLong(0L)

    @Volatile private var lastFrame: RuntimeFrame? = null

    fun assemble(opticalVariance: Float = 0f): RuntimeFrame {
        val id = frameCounter.incrementAndGet()

        val crossing = try {
            CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot()
        } catch (_: Throwable) { null }
        val lanes = crossing?.result?.lanes.orEmpty()
        val laneCount = lanes.size
        val viable = lanes.count { it.viable }
        val bestLane = lanes.firstOrNull { it.viable }
        val passTargetX = bestLane?.targetX ?: 0f
        val passTargetY = bestLane?.targetY ?: 0f
        val bestConf = lanes.maxOfOrNull { it.confidence } ?: 0f

        val telemetry = try { TelemetryRepository.current() } catch (_: Throwable) { null }
        val ballX = telemetry?.ballX ?: 0f
        val ballY = telemetry?.ballY ?: 0f
        val ballVx = telemetry?.ballVelocityX ?: 0f
        val ballVy = telemetry?.ballVelocityY ?: 0f
        val ballTrustNow = VisionTrust.ballTrust()
        val ballSeen = ballTrustNow > 0f && (ballX != 0f || ballY != 0f)

        /*
         * ROOT-CAUSE FIX (round 2): possession, not visibility.
         * BallPossessionEngine already computes true ownership every vision
         * cycle; the frame just never consumed it.
         */
        val possession = try {
            Phase3WorldStateStore.current().possession
        } catch (_: Throwable) { null }
        val possessionMinConf = 0.20f
        val hasBall =
            if (possession != null && possession.confidence >= possessionMinConf) {
                ballSeen && possession.hasPossession
            } else {
                ballSeen // cold-start fallback: old fresh-sighting semantics
            }

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val rawPlayers = scene?.trackedPlayers.orEmpty()

        // V6 VISION GUARD (field-proven over-count: players=30/opponents=30):
        // cap each side to 11 before zones/density/trust so downstream engines
        // never consume impossible head-counts.
        val assemblerPool = java.util.ArrayList<com.assistant.TrackedPlayer>(22)
        var oursCount = 0
        var theirsCount = 0
        for (p in rawPlayers) {
            if (p.isUserTeam) {
                if (oursCount < 11) { assemblerPool.add(p); oursCount++ }
            } else {
                if (theirsCount < 11) { assemblerPool.add(p); theirsCount++ }
            }
        }
        val players = assemblerPool
        val opponents = theirsCount

        // Real per-zone counts from tracked player positions (landscape thirds).
        val pitchH = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        var lo = 0; var lt = 0; var mo = 0; var mt = 0; var ro = 0; var rt = 0
        for (pl in players) {
            val mine = pl.isUserTeam
            when {
                pl.y < pitchH * 0.33f -> if (mine) lo++ else lt++
                pl.y > pitchH * 0.67f -> if (mine) ro++ else rt++
                else -> if (mine) mo++ else mt++
            }
        }
        val zones = ZoneDistribution(lo, lt, mo, mt, ro, rt)

        val enabled = try { SmartAssistRepository.enabled() } catch (_: Throwable) { false }
        val panic = try { SmartAssistRepository.panicActive() } catch (_: Throwable) { false }

        // GAP3: lane spread = share of lanes that are actually viable
        VisionTrust.stampLaneSpread(
            if (laneCount > 0) viable.toFloat() / laneCount.toFloat() else 0f
        )

        // Confidence follows the fresh ball sighting; lanes can only raise it.
        val rawConfidence = maxOf(bestConf, ballTrustNow).coerceIn(0f, 1f)
        val isTrustedFrame = VisionTrust.frameTrusted(players.size, opponents)
        val confidence = if (isTrustedFrame) {
            if (players.isNotEmpty() || opponents > 0 || bestConf > 0f || ballTrustNow > 0f) {
                rawConfidence.coerceAtLeast(0.5f)
            } else {
                rawConfidence
            }
        } else 0f

        VisionTrust.tickAndLog()

        val goalDetected =
            (scene?.goalDetected ?: false) &&
                (scene?.goalConfidence ?: 0f) > 0f &&
                (scene?.goalRightX ?: 0f) > (scene?.goalLeftX ?: 0f) &&
                (scene?.goalBottomY ?: 0f) > (scene?.goalTopY ?: 0f)

        val frame = RuntimeFrame(
            frameId = id,
            timestampMs = System.currentTimeMillis(),
            hasBall = hasBall,
            ballX = ballX,
            ballY = ballY,
            ballVelocityX = ballVx,
            ballVelocityY = ballVy,
            playerCount = players.size,
            opponentCount = opponents,
            laneCount = laneCount,
            viableLaneCount = viable,
            passTargetX = passTargetX,
            passTargetY = passTargetY,
            bestLaneConfidence = bestConf,
            defenderDensity = if (players.isNotEmpty())
                opponents.toFloat() / players.size else 0f,
            zones = zones,
            confidence = confidence,
            enabled = enabled,
            panic = panic,
            goalDetected = goalDetected,
            goalLeftX = scene?.goalLeftX ?: 0f,
            goalRightX = scene?.goalRightX ?: 0f,
            goalTopY = scene?.goalTopY ?: 0f,
            goalBottomY = scene?.goalBottomY ?: 0f,
            goalConfidence = scene?.goalConfidence ?: 0f,
            goalkeeperVisible = scene?.goalkeeperVisible ?: false,
            goalkeeperX = scene?.goalkeeperX ?: 0f,
            goalkeeperY = scene?.goalkeeperY ?: 0f,
            opticalVariance = opticalVariance.coerceIn(0f, 1f)
        )
        lastFrame = frame
        return frame
    }

    fun current(): RuntimeFrame? = lastFrame

    fun reset() {
        frameCounter.set(0L)
        lastFrame = null
    }

    fun frameRuntimeSnapshot(): Map<String, Any> {
        val f = lastFrame ?: return mapOf("frames" to frameCounter.get(), "state" to "cold")
        return mapOf(
            "frames" to frameCounter.get(),
            "frameId" to f.frameId,
            "hasBall" to f.hasBall,
            "players" to f.playerCount,
            "opponents" to f.opponentCount,
            "viableLanes" to f.viableLaneCount,
            "zones" to "L${f.zones.leftOurs}v${f.zones.leftTheirs} M${f.zones.midOurs}v${f.zones.midTheirs} R${f.zones.rightOurs}v${f.zones.rightTheirs}",
            "passTargetX" to f.passTargetX,
            "passTargetY" to f.passTargetY,
            "goalDetected" to f.goalDetected,
            "goalConfidence" to f.goalConfidence,
            "confidence" to f.confidence,
            "trusted" to f.trusted
        )
    }
}

/* ========
FrameDropCompensationEngine
======== */

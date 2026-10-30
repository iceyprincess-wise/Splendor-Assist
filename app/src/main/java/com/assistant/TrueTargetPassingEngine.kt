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

object TrueTargetPassingEngine {

    /**
     * @param retention 0..1 fraction along (start->end) to land on.
     * BUG FIX: retention now clamped. Old callers passed * 10f -> 10x overshoot.
     */
    fun optimize(
        startX: Float, startY: Float,
        endX: Float,   endY: Float,
        retention: Float
    ): PassingAssistResult {
        val r = retention.coerceIn(0f, 1f)
        val dx = endX - startX
        val dy = endY - startY
        return PassingAssistResult(
            correctedX       = (startX + dx * r).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback()),
            correctedY       = (startY + dy * r).coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback()),
            interceptionRisk = (1f - r).coerceIn(0f, 1f)
        )
    }

    /**
     * Predict where receiver will be when ball arrives, aim there.
     * Defeats SA interception where receiver runs away from ball path.
     */
    fun optimizeWithRunPrediction(
        ballX: Float, ballY: Float,
        receiverX: Float, receiverY: Float,
        receiverVx: Float, receiverVy: Float
    ): PassingAssistResult {
        val dist = hypot(
            (receiverX - ballX).toDouble(), (receiverY - ballY).toDouble()
        ).toFloat()
        val travelS = (dist / 750f + 0.14f).coerceIn(0f, 0.55f)
        val fps = 60f
        val predX = (receiverX + receiverVx * fps * travelS).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
        val predY = (receiverY + receiverVy * fps * travelS).coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())
        return optimize(ballX, ballY, predX, predY, 1.0f)
    }

    /**
     * Advanced Run & Match-Up Interception Escape Optimization
     */
    fun optimizeWithAdvancedTactics(
        ballX: Float, ballY: Float,
        receiverX: Float, receiverY: Float,
        receiverVx: Float, receiverVy: Float,
        defenderDensity: Float
    ): PassingAssistResult {
        val dist = hypot((receiverX - ballX).toDouble(), (receiverY - ballY).toDouble()).toFloat()
        val travelS = (dist / 750f + 0.14f).coerceIn(0f, 0.55f)
        val fps = 60f
        
        var predX = (receiverX + receiverVx * fps * travelS).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
        var predY = (receiverY + receiverVy * fps * travelS).coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())

        // Match-Up / Defender Shadow Offset
        if (defenderDensity > 0.5f) {
            val passAngle = atan2((predY - ballY).toDouble(), (predX - ballX).toDouble())
            // Offset pass vector 25px orthogonal to defender density cone
            predX += (sin(passAngle) * 25.0).toFloat()
            predY -= (cos(passAngle) * 25.0).toFloat()
        }

        val risk = (defenderDensity * 0.7f).coerceIn(0f, 1f)

        return PassingAssistResult(
            correctedX = predX.coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback()),
            correctedY = predY.coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback()),
            interceptionRisk = risk,
            requiresEscapeVector = defenderDensity > 0.8f
        )
    }

    fun interceptionVector(
        ballX: Float, ballY: Float,
        ballVelocityX: Float, ballVelocityY: Float,
        receiverX: Float, receiverY: Float
    ): Pair<Float, Float> {
        val lookAhead = 0.3f
        val px = ballX + ballVelocityX * lookAhead
        val py = ballY + ballVelocityY * lookAhead
        val dx = px - receiverX
        val dy = py - receiverY
        val m = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        return if (m > 0f) Pair((dx / m) * 100f, (dy / m) * 100f) else Pair(0f, 0f)
    }

    fun calculateDoublePressEscapeVector(
        carrierX: Float, carrierY: Float,
        presserAX: Float, presserAY: Float,
        presserBX: Float, presserBY: Float,
        strikerX: Float, strikerY: Float
    ): Pair<Float, Float> {
        val d = hypot((presserAX - presserBX).toDouble(), (presserAY - presserBY).toDouble())
        return if (d < 80.0) {
            val mx = (presserAX + presserBX) / 2f
            val my = (presserAY + presserBY) / 2f
            Pair(carrierX + (strikerX - mx) * 0.3f, carrierY + (strikerY - my) * 0.3f)
        } else {
            Pair(carrierX + (strikerX - carrierX) * 0.35f, carrierY + (strikerY - carrierY) * 0.35f)
        }
    }

    fun currentPassingGraph()                        = Phase3WorldStateStore.current().passingGraph
    fun currentThroughBallAnalysis()                 = Phase3WorldStateStore.current().throughBallAnalysis
    fun currentCrossingLaneAnalysis()                = Phase3WorldStateStore.current().crossingLaneAnalysis
    fun currentShootingLaneAnalysis()                = Phase3WorldStateStore.current().shootingLaneAnalysis
    fun currentBlockedLanePredictionAnalysis()       = Phase3WorldStateStore.current().blockedLanePredictionAnalysis
    fun currentDefenderInterceptionPredictionAnalysis() = Phase3WorldStateStore.current().defenderInterceptionPredictionAnalysis
    fun currentOpenSpaceDetectionResult()            = Phase3WorldStateStore.current().openSpaceDetectionResult
    fun currentReceiverRankingResult()               = Phase3WorldStateStore.current().receiverRankingResult
    fun currentRunPredictionResult()                 = Phase3WorldStateStore.current().runPredictionResult
    fun currentOverlapDetectionResult()              = Phase3WorldStateStore.current().overlapDetectionResult
    fun currentCounterattackDetectionResult()        = Phase3WorldStateStore.current().counterattackDetectionResult
    fun currentFastBreakDetectionResult()            = Phase3WorldStateStore.current().fastBreakDetectionResult
    fun currentOffsideRiskEstimationResult()         = Phase3WorldStateStore.current().offsideRiskEstimationResult
}

/* ========
VisionConfiguration
======== */

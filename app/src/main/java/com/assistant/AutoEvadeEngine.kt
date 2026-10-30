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

class AutoEvadeEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    companion object {
        @Volatile
        private var lastEvadeTimestamp = 0L

        // BASE COOLDOWN: 132ms (Optimized to align with exactly 4 server ticks at 33.33ms each)
        private const val BASE_EVADE_COOLDOWN_MS = 132L
        
        // NETCODE ALIGNMENT: Standard game server tick intervals
        private const val SERVER_TICK_MS = 33.33f
        
        // DISPLAY PACING: Hardware refresh rate boundaries
        private const val FRAME_120HZ_MS = 8.33f
    }

    fun monitorAttackingSpace(
        myPlayerX: Float, myPlayerY: Float,
        joystickX: Float, joystickY: Float,
        nearestOpponentX: Float, nearestOpponentY: Float,
        oppVx: Float, oppVy: Float,
        screenWidth: Float = com.assistant.vision.CameraProfile.captureWidthOrFallback(),
        screenHeight: Float = com.assistant.vision.CameraProfile.captureHeightOrFallback()
    ): Boolean {
        val now = System.currentTimeMillis()
        
        val humanizedCooldown = BASE_EVADE_COOLDOWN_MS
        if (now - lastEvadeTimestamp < humanizedCooldown) {
            return false
        }

        // [PREDICTIVE TRACKING] - Calculate opponent's future intersection based on momentum
        // Previously unused oppVx/oppVy now project the opponent 2 server ticks into the future (~66ms)
        val predictionScale = (SERVER_TICK_MS * 2f) / 1000f
        val predictedOppX = nearestOpponentX + (oppVx * predictionScale)
        val predictedOppY = nearestOpponentY + (oppVy * predictionScale)

        val closingDistance = hypot(
            predictedOppX - myPlayerX,
            predictedOppY - myPlayerY
        )

        // [AMPLIFIED INPUT EFFECTIVENESS] - Dynamic threat radius based on velocity magnitude
        val velocityMagnitude = hypot(oppVx, oppVy)
        val dynamicThreatMultiplier = 1.0f + (velocityMagnitude * 0.05f).coerceIn(0f, 0.4f)
        val pressRadiusThreshold = (screenHeight * 0.085f) * dynamicThreatMultiplier

        if (closingDistance >= pressRadiusThreshold) {
            return false
        }

        // Calculate geometric approach line vector from player to PREDICTED opponent position
        val approachDx = predictedOppX - myPlayerX
        val approachDy = predictedOppY - myPlayerY

        // Compute base 90-degree perpendicular escape angle
        val approachAngle = atan2(approachDy, approachDx)
        
        val escapeAngle = approachAngle + (PI.toFloat() / 2.0f)

        // Scale gesture path dynamically based on closing speed and screen size
        val evadeRadius = (screenHeight * 0.10f) * dynamicThreatMultiplier
        
        val evadeTargetX = (joystickX + (cos(escapeAngle) * evadeRadius)).coerceIn(0f, screenWidth)
        val evadeTargetY = (joystickY + (sin(escapeAngle) * evadeRadius)).coerceIn(0f, screenHeight)

        // [SERVER-TICK SYNC & FRAME-RATE OPTIMIZATION]
        // Base target: ~38-44ms gesture sweep.
        val rawDuration = 38f 
        
        // Snap duration to perfectly match 120Hz display boundaries (8.33ms intervals)
        val frameAlignedDuration = (ceil((rawDuration / FRAME_120HZ_MS).toDouble()) * FRAME_120HZ_MS).toLong()
        
        // Ensure duration structurally bridges at least one server packet boundary to guarantee registration
        val authoritativeDuration = frameAlignedDuration.coerceAtLeast(SERVER_TICK_MS.toLong() + 2L)

        val evadeRequest = ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = 7,
            startX = joystickX,
            startY = joystickY,
            endX = evadeTargetX,
            endY = evadeTargetY,
            duration = authoritativeDuration
        )

        if (ContributionRegistry.offer(evadeRequest)) {
            lastEvadeTimestamp = now
            RuntimeLogger.log(
                "AUTO_EVADE dynamic_slip v=(${evadeTargetX.toInt()}, ${evadeTargetY.toInt()}) dur=${authoritativeDuration}ms pred_dist=${closingDistance.toInt()}", 
                "SMART_ASSIST"
            )
            return true
        }

        return false
    }
}

/* ========
BallCandidate
======== */

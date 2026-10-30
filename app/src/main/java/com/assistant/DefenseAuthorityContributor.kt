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

object DefenseAuthorityContributor : GameplayContributor {
    override val engineName = "DefenseAuthority"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        var distance = (1f - frame.defenderDensity) * 500f
        var defX: Float? = null
        var defY: Float? = null

        try {
            val defState = Phase3WorldStateStore.current().defender
            if (defState.found && defState.distanceToAttacker < Float.MAX_VALUE) {
                distance = defState.distanceToAttacker.coerceIn(0f, 1200f)
                val trackedDef = defState.defender
                if (trackedDef != null) {
                    defX = trackedDef.x
                    defY = trackedDef.y
                }
            }
        } catch (_: Throwable) {
            // World state store fallback
        }

        val strength = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val recovery = frame.confidence * 10f
        val retention = frame.bestLaneConfidence * 10f

        val r = DefenseAuthorityEngine.evaluate(distance, strength, recovery, retention)

        // Rescaled authority math: maps 0..10 score spectrum cleanly to 0.0..1.0 range
        val normalizedContainment = r.containment / 10f
        val normalizedInterception = r.interception / 10f
        val rawAuthority = (normalizedContainment * 0.5f) + (normalizedInterception * 0.5f)
        val authority = rawAuthority.coerceIn(0.10f, 1.0f)

        val ballX = frame.ballX
        val ballY = frame.ballY

        val targetX: Float
        val targetY: Float

        if (r.containment > r.interception) {
            // Containment positioning: vector goal-side using defender coordinates when present
            // Positive user attack points right, so our own goal is the left edge.
            // Low-confidence inference preserves the historical left-edge target.
            val attackDirection = AttackAxisModel.directionIfConfident()
            val fallbackGoalX = 0f
            val goalX = if (attackDirection > 0) fallbackGoalX else if (attackDirection < 0) com.assistant.vision.CameraProfile.captureWidthOrFallback() else fallbackGoalX
            val goalY = 540f
            val refX = defX ?: ballX
            val refY = defY ?: ballY

            val dx = (goalX - refX).toDouble()
            val dy = (goalY - refY).toDouble()
            val angle = atan2(dy, dx)
            val containOffset = 60f + ((1f - (distance / 1200f)) * 40f)

            targetX = (refX + cos(angle).toFloat() * containOffset).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
            targetY = (refY + sin(angle).toFloat() * containOffset).coerceIn(0f, 1080f)
        } else {
            // Direct press / interception targeting
            targetX = ballX
            targetY = ballY
        }

        return EngineContribution(
            engineName,
            ActionClass.DEFEND,
            targetX,
            targetY,
            authority,
            frame.confidence,
            32L
        )
    }
}

/* ========
DefenseContributor
======== */

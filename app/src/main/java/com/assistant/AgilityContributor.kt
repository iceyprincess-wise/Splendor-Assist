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

object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    // ZERO-ALLOCATION JNI BUFFER: 6 floats produced by native C ABI
    // [0]=stabilityBoost, [1]=controlRetentionBoost, [2]=turnAssist, [3]=shieldAngle, [4]=shieldDuration, [5]=shieldActive
    private val nativeAgilityOut = FloatArray(6)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val observed = observeAgility(frame) ?: return null
        val ballX = observed.x
        val ballY = observed.y

        val success = com.assistant.NativeBridge.nativeComputeAgilityPhysics(
            observed.speed, observed.distance, observed.headingDegrees, observed.confidence, observed.turnIntensity,
            ballX, ballY, observed.opponentX, observed.opponentY,
            nativeAgilityOut,
            100.0f, 220.0f, 500.0f, 1200.0f,
            observed.referenceSpeed, 4.0f, 6.0f, 3.0f, 2.0f,
            45.0f, 0.3f, 0.5f
        )

        if (!success || nativeAgilityOut[5] < 0.1f) return null // Native layer determined no active shield/agile move

        val angleRad = Math.toRadians(nativeAgilityOut[3].toDouble())
        val turnAssist = nativeAgilityOut[2].coerceIn(0f, 1f)
        val pushDist = 75.0f + turnAssist * 45.0f
        val calculatedTargetX = (ballX + kotlin.math.cos(angleRad) * pushDist).toFloat().coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
        val calculatedTargetY = (ballY + kotlin.math.sin(angleRad) * pushDist).toFloat().coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())
        val authority = (nativeAgilityOut[0] / 15.0f).coerceIn(0.1f, 0.95f)
        val confidence = nativeAgilityOut[1].coerceIn(0.1f, 1f)
        val durationHintMs = nativeAgilityOut[4].toLong().coerceIn(16L, 100L)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = calculatedTargetX,
            targetY = calculatedTargetY,
            authority = authority,
            confidence = confidence,
            durationHintMs = durationHintMs,
            aimOriginX = observed.x, aimOriginY = observed.y, aimEntityId = observed.carrierId
        )
    }
}

/* ========
AttackingVectorContributor
======== */

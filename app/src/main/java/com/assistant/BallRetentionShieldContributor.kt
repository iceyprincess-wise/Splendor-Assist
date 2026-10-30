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

object BallRetentionShieldContributor : GameplayContributor {
    override val engineName   = "BallRetentionShield"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)
    
    // ZERO-ALLOCATION JNI BUFFER: 5 floats produced by native C ABI
    // [0]=active, [1]=shieldX, [2]=shieldY, [3]=authority, [4]=risk
    private val nativeBuffer = FloatArray(5)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // SPLENDOR_BALL_RETENTION_REAL_TELEMETRY_V1 - feed the frame's real
        // telemetry. The previous call passed only three values and the bridge
        // invented the rest (confidence 1.0, 3/3 lanes, zeroed zones), so this
        // engine could never see a blocked lane or a low-confidence frame.
        if (!frame.trusted || !frame.hasBall) return null
        val zones = frame.zones
        val success = com.assistant.NativeBridge.nativeComputeBallRetention(
            hasBall = frame.hasBall,
            trusted = frame.trusted,
            confidence = frame.confidence,
            ballX = frame.ballX, ballY = frame.ballY,
            viableLaneCount = frame.viableLaneCount,
            laneCount = frame.laneCount,
            passTargetX = frame.passTargetX, passTargetY = frame.passTargetY,
            zonesLeftTheirs = zones.leftTheirs,
            zonesMidTheirs = zones.midTheirs,
            zonesRightTheirs = zones.rightTheirs,
            defenderDensity = frame.defenderDensity,
            resultBuffer = nativeBuffer
        )

        if (!success || nativeBuffer[0] < 0.1f) return null // Active flag check
        
        val authority = nativeBuffer[3].coerceIn(0.1f, 1.0f)
        val confidence = (1.0f - nativeBuffer[4]).coerceIn(0.1f, 1.0f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = nativeBuffer[1].coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback()),
            targetY = nativeBuffer[2].coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback()),
            authority = authority,
            confidence = confidence,
            durationHintMs = 15L // Maximum reaction speed
        )
    }
}

/* ========
BuildUpPressContributor
======== */

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

object SpeedCompensationContributor : GameplayContributor {
    override val engineName = "SpeedCompensation"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null

        // distance: proxy from defenderDensity — high density = close threats
        val distance = ((1f - frame.defenderDensity) * 800f).coerceIn(0f, 1000f)
        // strength: how much pressure — scaled to 0..100
        val strength = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        // angle: derive from ball-to-pass-target vector; 0 when no lane
        val angle = if (frame.passTargetX > 0f || frame.passTargetY > 0f) {
            val dx = frame.passTargetX - frame.ballX
            val dy = frame.passTargetY - frame.ballY
            Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        } else 0f

        val result = SpeedCompensationEngine.compensate(distance, angle, strength)

        // authority from executionBoost — max is 35.0 in engine
        val authority = (result.executionBoost / 35f).coerceIn(0f, 1f)
        if (authority <= 0f) return null

        // Target: toward pass lane when open, toward ball when defending
        val targetX = if (frame.hasBall && frame.passTargetX > 0f)
            frame.passTargetX else frame.ballX.coerceAtLeast(0f)
        val targetY = if (frame.hasBall && frame.passTargetY > 0f)
            frame.passTargetY else frame.ballY.coerceAtLeast(0f)

        return EngineContribution(
            engine = engineName,
            actionClass = if (frame.hasBall) ActionClass.MOVE else ActionClass.DEFEND,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = run {
                // PHASE3: thermal + battery now properly wired into duration scaling
                val aggro = AdapterSignalBus.filterAggression
                val p = when {
                    AdapterSignalBus.thermalIsSevere -> 0.4f          // severe heat: very short gestures
                    AdapterSignalBus.batteryCritical -> 0.5f          // critical battery: reduce load
                    AdapterSignalBus.lagIsChoking || AdapterSignalBus.memoryIsCritical -> 0.5f
                    AdapterSignalBus.inputIsLagging -> 0.7f
                    AdapterSignalBus.stutterIsSevere -> 0.65f         // now actually works (bus was never published before)
                    AdapterSignalBus.lagVerdict == "JITTERY" || AdapterSignalBus.memoryIsUnderPressure -> 0.8f
                    // Live adaptive brake: PerformanceEngineService publishes
                    // executionBrake from measured load shedding, but nothing on
                    // the execution path read it, so the brake was a decorative
                    // flag. Honour it here by shortening the gesture window.
                    AdapterSignalBus.executionIsFullyBraked -> 0.55f
                    AdapterSignalBus.executionIsBraked -> 0.8f
                    aggro > 1.5f -> 1.2f // EXTREMIST OVERRIDE: Force longer holds for guaranteed execution
                    else -> 1.0f
                }
                (30L * p).toLong().coerceIn(12L, if (aggro > 1.5f) 90L else 60L)
            }
        )
    }
}
/* ======
SpeedCompensationContributor Anchor
====== */

/* ========
SupportContributor
======== */

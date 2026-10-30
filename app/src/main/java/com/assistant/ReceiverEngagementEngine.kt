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

object ReceiverEngagementEngine {

    fun evaluate(
        distance:Float,
        retention:Float
    ):ReceiverEngagementResult {

        val distanceFactor =
            (distance.coerceIn(0f,1000f) / 1000f)

        val confidence =
            (
                0.45f +
                retention * 0.35f +
                (1f - distanceFactor) * 0.20f
            ).coerceIn(0f,1f)

        val engagementBoost =
            1f +
            (retention * 0.70f) +
            ((1f - distanceFactor) * 0.30f)

        val interceptionRisk =
            (
                (1f - retention) * 0.70f +
                distanceFactor * 0.30f
            ).coerceIn(0f,1f)

        return ReceiverEngagementResult(
            engagementBoost = engagementBoost,
            interceptionRisk = interceptionRisk,
            confidence = confidence
        )
    }
}

/* ========
RuntimeHealthMonitor
======== */
/*
 * RuntimeHealthMonitor answers one question:
 * "Is the runtime alive, stale, or degraded right now?"
 *
 * It does not own gameplay logic. It reads health surfaces already present
 * in the architecture and reports only REAL failures:
 *  - frame/decision freshness is measured by counter PROGRESS between
 *    evaluations (the old math compared 'now' against a timestamp taken
 *    inside the same call, so frame-stale could never fire and
 *    decision-stale fired for the wrong reason)
 *  - the decision loop runs once per assembled frame; when the frame flow
 *    itself is paused (menus, control rooms, game off screen) the loop is
 *    idle by design - the cause is reported as frame-stale, never blamed
 *    on the decision loop
 *  - a dispatcher that has never been asked to dispatch is idle, not stale
 *  - booster health reads the CROSS-PROCESS adapter heartbeats: the
 *    adapters live in their own processes, so only the persisted
 *    heartbeats are visible here, and only FRESH ones count
 */

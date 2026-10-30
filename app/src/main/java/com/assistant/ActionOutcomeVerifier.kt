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

object ActionOutcomeVerifier {
    private data class PendingRecord(
        val request: ExecutionRequest,
        val frame: RuntimeFrame,
        var dispatchTimestamp: Long = 0L,
        var confirmed: Boolean = false
    )

    private val pendingRecords = java.util.concurrent.ConcurrentHashMap<String, PendingRecord>()

    private val totalDispatched = AtomicLong(0L)
    private val effectObservedCount = AtomicLong(0L)
    private val effectNotObservedCount = AtomicLong(0L)

    fun recordDispatch(request: ExecutionRequest, frame: RuntimeFrame) {
        pendingRecords[request.actionId] = PendingRecord(request, frame)
        totalDispatched.incrementAndGet()
    }

    fun confirmDispatch(actionId: String) {
        pendingRecords[actionId]?.let {
            it.dispatchTimestamp = System.currentTimeMillis()
            it.confirmed = true
        }
    }

    fun verify(currentFrame: RuntimeFrame) {
        val iterator = pendingRecords.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val record = entry.value
            
            if (!record.confirmed) {
                if (System.currentTimeMillis() - record.request.timestamp > 500L) {
                    iterator.remove()
                }
                continue
            }

            val age = currentFrame.timestampMs - record.dispatchTimestamp

            if (age < 30L) continue
            if (age > 350L) {
                effectNotObservedCount.incrementAndGet()
                GestureExecutionAuthority.recordEffectObserved(actionId = record.request.actionId, observed = false)
                iterator.remove()
                continue
            }

            val observed = when (record.request.phase) {
                ActionClass.PASS.ordinal -> {
                    val dx = currentFrame.ballX - record.frame.ballX
                    val dy = currentFrame.ballY - record.frame.ballY
                    kotlin.math.hypot(dx, dy) > 10f
                }
                ActionClass.SHOT.ordinal -> {
                    currentFrame.goalDetected || kotlin.math.hypot(currentFrame.ballVelocityX, currentFrame.ballVelocityY) > 15f
                }
                ActionClass.CROSS.ordinal -> {
                    kotlin.math.hypot(currentFrame.ballX - record.frame.ballX, currentFrame.ballY - record.frame.ballY) > 20f
                }
                ActionClass.DEFEND.ordinal, ActionClass.KEEPER.ordinal -> {
                    currentFrame.defenderDensity <= record.frame.defenderDensity || !currentFrame.panic
                }
                ActionClass.MOVE.ordinal, ActionClass.EVADE.ordinal -> {
                    kotlin.math.hypot(currentFrame.ballX - record.frame.ballX, currentFrame.ballY - record.frame.ballY) > 3f
                }
                else -> true
            }

            if (observed) {
                effectObservedCount.incrementAndGet()
                GestureExecutionAuthority.recordEffectObserved(actionId = record.request.actionId, observed = true)
            } else {
                effectNotObservedCount.incrementAndGet()
                GestureExecutionAuthority.recordEffectObserved(actionId = record.request.actionId, observed = false)
            }
            iterator.remove()
        }
    }

    fun diagnostics(): Map<String, Any> = mapOf(
        "dispatched" to totalDispatched.get(),
        "effectObserved" to effectObservedCount.get(),
        "effectNotObserved" to effectNotObservedCount.get()
    )
}

/* ========
ConAIEngine Meta-Arbiter
======== */

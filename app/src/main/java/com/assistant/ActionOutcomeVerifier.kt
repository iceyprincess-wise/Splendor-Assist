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
        var dispatchTimestamp: Long = 0L
    )
    private val pendingRecords = HashMap<String, PendingRecord>()
    private val totalDispatched = AtomicLong(0L)
    private val effectObservedCount = AtomicLong(0L)
    private val effectNotObservedCount = AtomicLong(0L)

    @Synchronized
    fun recordDispatch(request: ExecutionRequest, frame: RuntimeFrame) {
        pendingRecords[request.actionId] = PendingRecord(request, frame)
    }

    @Synchronized
    fun confirmDispatch(actionId: String) {
        pendingRecords[actionId]?.let {
            if (it.dispatchTimestamp == 0L) {
                it.dispatchTimestamp = System.currentTimeMillis()
                totalDispatched.incrementAndGet()
            }
        }
    }

    @Synchronized
    fun cancelDispatch(actionId: String) { pendingRecords.remove(actionId) }

    @Synchronized
    fun verify(currentFrame: RuntimeFrame) {
        val now = System.currentTimeMillis()
        val iterator = pendingRecords.values.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next()
            if (record.dispatchTimestamp == 0L) {
                if (now - record.request.timestamp > 500L) iterator.remove()
                continue
            }
            val age = currentFrame.timestampMs - record.dispatchTimestamp
            if (now - record.dispatchTimestamp > 350L) {
                effectNotObservedCount.incrementAndGet()
                GestureExecutionAuthority.recordEffectObserved(record.request.actionId, false)
                iterator.remove()
                continue
            }
            if (age < 30L || currentFrame.frameId <= record.frame.frameId || !currentFrame.trusted) continue
            val displacement = kotlin.math.hypot(currentFrame.ballX - record.frame.ballX,
                currentFrame.ballY - record.frame.ballY)
            val observed = when (record.request.phase) {
                ActionClass.PASS.ordinal -> displacement > 10f
                ActionClass.SHOT.ordinal -> displacement > 10f &&
                    kotlin.math.hypot(currentFrame.ballVelocityX, currentFrame.ballVelocityY) >
                    kotlin.math.hypot(record.frame.ballVelocityX, record.frame.ballVelocityY) + 5f
                ActionClass.CROSS.ordinal -> displacement > 20f
                ActionClass.DEFEND.ordinal, ActionClass.KEEPER.ordinal ->
                    currentFrame.defenderDensity < record.frame.defenderDensity ||
                        (record.frame.panic && !currentFrame.panic) ||
                        (!record.frame.hasBall && currentFrame.hasBall)
                ActionClass.MOVE.ordinal, ActionClass.EVADE.ordinal -> displacement > 3f
                else -> false
            }
            // Visual change is correlation, not proof of a goal or game-state mutation.
            // Keep waiting for delayed effects throughout the observation window.
            if (observed) {
                effectObservedCount.incrementAndGet()
                GestureExecutionAuthority.recordEffectObserved(record.request.actionId, true)
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun reset() {
        pendingRecords.clear()
        totalDispatched.set(0L)
        effectObservedCount.set(0L)
        effectNotObservedCount.set(0L)
    }

    @Synchronized
    fun diagnostics(): Map<String, Any> = mapOf(
        "dispatched" to totalDispatched.get(),
        "effectObserved" to effectObservedCount.get(),
        "effectNotObserved" to effectNotObservedCount.get(),
        "pending" to pendingRecords.size,
        "evidenceKind" to "visual-change-correlation"
    )
}

/* ========
ConAIEngine Meta-Arbiter
======== */

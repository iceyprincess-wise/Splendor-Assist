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
    private data class Pending(val request: ExecutionRequest, val frame: RuntimeFrame, val at: Long = System.nanoTime())
    private val pending = linkedMapOf<String, Pending>()
    private val policy = com.assistant.execution.OutcomePolicy()
    private var dispatched = 0L; private var macroObserved = 0L; private var heuristicFailure = 0L
    @Synchronized fun recordDispatch(request: ExecutionRequest, frame: RuntimeFrame) {
        if (pending.size >= 64) cancelDispatch(pending.keys.first())
        if (!pending.containsKey(request.actionId)) pending[request.actionId] = Pending(request, frame)
    }
    @Synchronized fun confirmDispatch(actionId: String) {
        val item = pending.remove(actionId) ?: return
        val phase = item.request.actionClass ?: if (item.request.source == ExecutionSource.GOALKEEPER) ActionClass.KEEPER else return
        policy.dispatched(actionId, phase, item.frame.ownerId, item.frame.hasBall, System.nanoTime())
        dispatched++
    }
    @Synchronized fun cancelDispatch(actionId: String) { pending.remove(actionId); policy.cancel(actionId) }
    fun cooling(phase: ActionClass) = policy.cooling(phase, System.nanoTime())
    fun confidenceFactor(phase: ActionClass) = policy.confidenceFactor(phase)
    @Synchronized fun verify(currentFrame: RuntimeFrame) {
        if (com.assistant.execution.UiExecutionGate.blocked()) {
            pending.clear(); policy.abandonPending(); return
        }
        val now = System.nanoTime()
        pending.entries.removeAll { now - it.value.at > 500_000_000L }
        for ((id, observed) in policy.observe(currentFrame.ownerId, currentFrame.hasBall, currentFrame.trusted, now, currentFrame.capturedAtNanos)) {
            if (observed) macroObserved++ else heuristicFailure++
            GestureExecutionAuthority.recordEffectObserved(id, observed)
            RuntimeLogger.log("${if (observed) "Macro_State_Correlation" else "Heuristic_Gesture_Failure"} actionId=$id", "FEEDBACK")
        }
    }
    @Synchronized fun reset() { pending.clear(); policy.reset(); dispatched = 0; macroObserved = 0; heuristicFailure = 0 }
    @Synchronized fun diagnostics(): Map<String, Any> = mapOf("dispatched" to dispatched,
        "macroObserved" to macroObserved, "heuristicFailure" to heuristicFailure,
        "pending" to pending.size, "evidenceKind" to "macro-state-correlation-not-internal-success")
}

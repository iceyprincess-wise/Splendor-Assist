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

object CrowdingZoneDetector {

    private const val DENSITY_THRESHOLD = 0.75f
    private const val CONFIDENCE_THRESHOLD = 0.45f
    private const val DENSITY_WEIGHT = 0.60f
    private const val INV_CONF_WEIGHT = 0.40f

    private val detections = AtomicLong(0L)

    @Volatile var inCrowdedZone: Boolean = false; private set
    @Volatile var crowdingLevel: Float = 0f; private set
    @Volatile private var enabled = true
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        try { prefs?.edit()?.putBoolean("crowding_enabled", value)?.apply() } catch (_: Throwable) {}
        if (!value) reset()
    }

    fun isEnabled(): Boolean = enabled

    fun init(context: android.content.Context) {
        try {
            val p = context.applicationContext.getSharedPreferences("splendor_engine_toggles", 0)
            prefs = p
            enabled = p.getBoolean("crowding_enabled", true)
        } catch (_: Throwable) {}
    }

    /**
     * Evaluate crowding from the current frame.
     * Returns true when in a penalty-box / corner crowded zone.
     */
    fun evaluate(frame: RuntimeFrame): Boolean {
        if (!enabled) {
            inCrowdedZone = false
            crowdingLevel = 0f
            AdapterSignalBus.publishCrowdingZone(false, 0f)
            return false
        }
        if (!frame.trusted) {
            inCrowdedZone = false
            crowdingLevel = 0f
            AdapterSignalBus.publishCrowdingZone(false, 0f)
            return false
        }

        val density = frame.defenderDensity.coerceIn(0f, 1f)
        val invConf = (1f - frame.confidence.coerceIn(0f, 1f))
        val level = (density * DENSITY_WEIGHT + invConf * INV_CONF_WEIGHT)
            .coerceIn(0f, 1f)

        val zone =
            density > DENSITY_THRESHOLD &&
                frame.confidence < CONFIDENCE_THRESHOLD

        inCrowdedZone = zone
        crowdingLevel = level
        AdapterSignalBus.publishCrowdingZone(zone, level)

        if (zone) {
            val count = detections.incrementAndGet()
            if (count % 60L == 0L) {
                RuntimeLogger.log(
                    "CROWDING_ZONE: density=%.2f confidence=%.2f level=%.2f #%d"
                        .format(density, frame.confidence, level, count),
                    "CROWDING"
                )
            }
        }

        return zone
    }

    fun reset() {
        inCrowdedZone = false
        crowdingLevel = 0f
        detections.set(0L)
        AdapterSignalBus.publishCrowdingZone(false, 0f)
    }

    fun diagnostics(): Map<String, Any> = mapOf(
        "inCrowdedZone" to inCrowdedZone,
        "crowdingLevel"  to crowdingLevel,
        "detections"     to detections.get(),
        "enabled" to enabled
    )
}

/* ========
DecisionResult
======== */

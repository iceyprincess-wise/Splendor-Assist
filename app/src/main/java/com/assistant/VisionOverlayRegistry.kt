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

object VisionOverlayRegistry {

    @Volatile
    private var state = VisionOverlayRegistryState()

    fun current(): VisionOverlayRegistryState = state

    fun refresh() {
        state = VisionOverlayRegistryState()
    }

    fun enableAll() {
        VisionConfigurationEngine.update {
            it.copy(
                debugOverlayEnabled = true,
                boundingBoxOverlayEnabled = true,
                ballOverlayEnabled = true,
                playerOverlayEnabled = true,
                goalOverlayEnabled = true,
                confidenceHeatmapEnabled = true
            )
        }
        VisionDebugOverlay.refresh()
        refresh()
    }

    fun disableAll() {
        VisionConfigurationEngine.update {
            it.copy(
                debugOverlayEnabled = false,
                boundingBoxOverlayEnabled = false,
                ballOverlayEnabled = false,
                playerOverlayEnabled = false,
                goalOverlayEnabled = false,
                confidenceHeatmapEnabled = false
            )
        }
        VisionDebugOverlay.refresh()
        refresh()
    }

    // ---- SELF-MASK (GAP1A): overlays we drew ourselves, excluded from ingestion ----
    private val selfRects = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Rect>()

    fun publishBounds(tag: String, r: android.graphics.Rect) {
        selfRects[tag] = android.graphics.Rect(r)
    }

    fun clearBounds(tag: String) {
        selfRects.remove(tag)
    }

    fun isSelfDrawn(x: Int, y: Int): Boolean {
        if (selfRects.isEmpty()) return false
        for (r in selfRects.values) if (r.contains(x, y)) return true
        return false
    }

    fun isSelfDrawn(x: Float, y: Float): Boolean = isSelfDrawn(x.toInt(), y.toInt())

    fun selfMask(): List<android.graphics.Rect> = selfRects.values.toList()

    fun selfMaskCount(): Int = selfRects.size

}

/* ========
PassingLane
======== */

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

object FrameScanner {

    // Reusable buffer to prevent per-frame ~1.6MB ByteArray allocations (Phase 2)
    private var reusableByteArray: ByteArray? = null
    
    // Reusable buffer for packed pixel samples to prevent per-frame object allocations (Phase 4)
    private var reusableSamples: LongArray? = null

    data class PixelSampleBuffer(
        val data: LongArray,
        val count: Int
    )

    // =========================================================================
    // OMEGA UPGRADE CORE CONSTANTS
    // =========================================================================
    
    private const val WEIGHT_R = 13933  // ~0.2126f
    private const val WEIGHT_G = 46871  // ~0.7152f
    private const val WEIGHT_B = 4732   // ~0.0722f
    private const val LUMINANCE_SHIFT = 16

    fun scan(
        frame: FrameNormalizer.NormalizedFrame,
        threshold: Float = 0.50f
    ): PixelSampleBuffer {
        val width = frame.width
        val height = frame.height

        if (width <= 0 || height <= 0) return PixelSampleBuffer(LongArray(0), 0)

        val buffer: ByteBuffer = frame.buffer.duplicate()
        buffer.rewind()

        val limit = buffer.remaining()
        if (limit == 0) return PixelSampleBuffer(LongArray(0), 0)

        // PHASE 1: BULK MEMORY TRANSFER (ZERO-ALLOC REUSE)
        var frameBytes = reusableByteArray
        if (frameBytes == null || frameBytes.size < limit) {
            frameBytes = ByteArray(limit)
            reusableByteArray = frameBytes
        }
        buffer.get(frameBytes, 0, limit)

        val thresholdInt = (threshold * 255.0f).roundToInt().coerceIn(0, 255)
        
        // PHASE 4: ZERO-ALLOC PIXEL SAMPLES
        val maxPixels = width * height
        var samples = reusableSamples
        if (samples == null || samples.size < maxPixels) {
            samples = LongArray(maxPixels)
            reusableSamples = samples
        }
        
        var sampleCount = 0

        val required = (height - 1L) * frame.rowStride + (width - 1L) * frame.pixelStride + 3
        if (frame.pixelStride < 3 || frame.rowStride.toLong() < width.toLong() * frame.pixelStride || required > limit)
            return PixelSampleBuffer(samples, 0)
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * frame.rowStride + x * frame.pixelStride
            val r = frameBytes[index].toInt() and 0xFF
            val g = frameBytes[index + 1].toInt() and 0xFF
            val b = frameBytes[index + 2].toInt() and 0xFF
            if (ForegroundPixel.retained(r, g, b, thresholdInt)) {
                // The adaptive-noise / server-tick-sync parameters were
                // removed: no production or test caller ever passed them,
                // so pixel coordinates were never actually jittered or
                // rescaled despite the code implying otherwise.
                samples[sampleCount++] = (x.toLong() shl 40) or (y.toLong() shl 24) or
                    (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
            }
        }

        return PixelSampleBuffer(samples, sampleCount)
    }
}

/* ========
GameStateBuilder
======== */

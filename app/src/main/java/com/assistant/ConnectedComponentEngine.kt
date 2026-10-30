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

object ConnectedComponentEngine {

    class Blob(
        var minX: Int,
        var minY: Int,
        var maxX: Int,
        var maxY: Int,
        var pixelCount: Int,
        var averageRed: Float,
        var averageGreen: Float,
        var averageBlue: Float
    )

    private val OFFSETS = arrayOf(
        -1 to -1, 0 to -1, 1 to -1,
        -1 to  0,          1 to  0,
        -1 to  1, 0 to  1, 1 to  1
    )

    // Reusable queue to prevent per-component ArrayDeque allocation (Phase 3)
    private val reusableQueue = ThreadLocal.withInitial { java.util.ArrayDeque<Int>(1024) }
    private val threadLocalLookup = ThreadLocal.withInitial { HashMap<Int, Int>(2048) }
    private val threadLocalVisited = ThreadLocal.withInitial { HashSet<Int>(2048) }

    fun extract(
        buffer: FrameScanner.PixelSampleBuffer
    ): List<Blob> {
        val count = buffer.count
        if (count == 0) return emptyList()
        
        val data = buffer.data

        // Phase 3 optimization: packed Int keys (x shl 16 or y)
        // Map stores the INDEX in the PixelSampleBuffer to retrieve RGB values
        val lookup = threadLocalLookup.get()!!
        lookup.clear()
        for (i in 0 until count) {
            val packed = data[i]
            val x = (packed shr 40 and 0xFFFF).toInt()
            val y = (packed shr 24 and 0xFFFF).toInt()
            val key = (x shl 16) or y
            lookup[key] = i
        }

        val visited = threadLocalVisited.get()!!
        visited.clear()
        val blobs = ArrayList<Blob>()
        val queue = reusableQueue.get()!!

        for (i in 0 until count) {
            val packed = data[i]
            val startX = (packed shr 40 and 0xFFFF).toInt()
            val startY = (packed shr 24 and 0xFFFF).toInt()
            val startKey = (startX shl 16) or startY

            if (!visited.add(startKey))
                continue

            queue.clear()
            queue.add(startKey)

            var minX = startX
            var minY = startY
            var maxX = startX
            var maxY = startY

            var pixelCount = 0
            var r = 0f
            var g = 0f
            var b = 0f

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                
                val idx = lookup[current] ?: continue
                val currentPacked = data[idx]
                
                val cx = (currentPacked shr 40 and 0xFFFF).toInt()
                val cy = (currentPacked shr 24 and 0xFFFF).toInt()
                val cr = (currentPacked shr 16 and 0xFF).toInt()
                val cg = (currentPacked shr 8 and 0xFF).toInt()
                val cb = (currentPacked and 0xFF).toInt()

                pixelCount++

                if (cx < minX) minX = cx
                if (cy < minY) minY = cy
                if (cx > maxX) maxX = cx
                if (cy > maxY) maxY = cy

                r += cr
                g += cg
                b += cb

                for ((dx, dy) in OFFSETS) {
                    val nx = cx + dx
                    val ny = cy + dy
                    val nextKey = (nx shl 16) or ny

                    if (visited.add(nextKey) && lookup.containsKey(nextKey)) {
                        queue.add(nextKey)
                    }
                }
            }

            blobs.add(
                Blob(
                    minX = minX,
                    minY = minY,
                    maxX = maxX,
                    maxY = maxY,
                    pixelCount = pixelCount,
                    averageRed = r / pixelCount,
                    averageGreen = g / pixelCount,
                    averageBlue = b / pixelCount
                )
            )
        }

        return blobs
    }
}

/* ========
CounterPressRecognitionEngine
======== */

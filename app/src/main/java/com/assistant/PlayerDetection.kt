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

object PlayerDetector {

    private const val MIN_PIXEL_COUNT = 10
    private const val MIN_ASPECT_RATIO = 0.15f
    private const val MIN_CANDIDATE_CONFIDENCE = 0.30f
    private const val NMS_OVERLAP_THRESHOLD = 0.40f
    
    private val rawPool = Array(1000) { PlayerDetection(0f, 0f, 0f, false) }
    private val keptPool = Array(1000) { PlayerDetection(0f, 0f, 0f, false) }
    private val suppressed = BooleanArray(1000)
    private val resultList = object : java.util.AbstractList<PlayerDetection>() {
        var count = 0
        override val size get() = count
        override fun get(index: Int): PlayerDetection {
            if (index < 0 || index >= count) {
                val trace = Thread.currentThread().stackTrace.joinToString("\n") { "  at $it" }
                try { com.assistant.diagnostic.RuntimeLogger.log("FATAL OOB: index=$index, count=$count\n$trace", "PLAYER_DETECTOR") } catch (_: Throwable) {}
                throw IndexOutOfBoundsException("length=${keptPool.size}; index=$index\nCaller:\n$trace")
            }
            return keptPool[index]
        }
        fun bind(c: Int) { count = c }
    }

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): PlayerDetectionResult {
        if (blobs.isEmpty()) {
            return PlayerDetectionResult(
                detected = false,
                playerCount = 0,
                confidence = 0f,
                detections = emptyList()
            )
        }

        var rawCount = 0

        for (blob in blobs) {
            if (blob.pixelCount < MIN_PIXEL_COUNT) continue

            val width = (blob.maxX - blob.minX + 1).coerceAtLeast(1)
            val height = (blob.maxY - blob.minY + 1).coerceAtLeast(1)
            val area = width.toFloat() * height.toFloat()
            val density = (blob.pixelCount.toFloat() / area).coerceIn(0f, 1f)
            val shortSide = minOf(width, height).toFloat()
            val longSide = maxOf(width, height).toFloat()
            val aspectRatio = if (longSide > 0f) shortSide / longSide else 0f

            if (aspectRatio < MIN_ASPECT_RATIO) continue

            val centerX = (blob.minX + blob.maxX) * 0.5f
            val centerY = (blob.minY + blob.maxY) * 0.5f

            // OMEGA FIX: Dynamic Wide Camera Depth Scaling
            val depthFactor = if (centerY < 400f) 0.25f else 1.0f
            val effectivePixelCount = blob.pixelCount / depthFactor

            val jersey = JerseyColorSegmentation.classify(
                blob.averageRed,
                blob.averageGreen,
                blob.averageBlue
            )

            val sizeScore = (effectivePixelCount / 64f).coerceIn(0f, 1f)
            val densityScore = density.coerceIn(0f, 1f)
            val shapeScore = ((aspectRatio - MIN_ASPECT_RATIO) /
                (1f - MIN_ASPECT_RATIO)).coerceIn(0f, 1f)
            val jerseyScore = jersey.confidence.coerceIn(0f, 1f)

            val confidence = (
                sizeScore * 0.30f +
                densityScore * 0.25f +
                shapeScore * 0.20f +
                jerseyScore * 0.25f
            ).coerceIn(0f, 1f)

            if (confidence < MIN_CANDIDATE_CONFIDENCE) continue

            if (rawCount < rawPool.size) {
                val pd = rawPool[rawCount]
                pd.x = centerX
                pd.y = centerY
                pd.confidence = confidence
                pd.isUserTeam = jersey.team == JerseyColorSegmentation.Team.USER
                rawCount++
            }
        }

        // In-place sort rawPool by confidence descending
        for (i in 0 until rawCount) {
            for (j in i + 1 until rawCount) {
                if (rawPool[j].confidence > rawPool[i].confidence) {
                    val tempX = rawPool[i].x; val tempY = rawPool[i].y; val tempC = rawPool[i].confidence; val tempT = rawPool[i].isUserTeam
                    rawPool[i].x = rawPool[j].x; rawPool[i].y = rawPool[j].y; rawPool[i].confidence = rawPool[j].confidence; rawPool[i].isUserTeam = rawPool[j].isUserTeam
                    rawPool[j].x = tempX; rawPool[j].y = tempY; rawPool[j].confidence = tempC; rawPool[j].isUserTeam = tempT
                }
            }
        }

        // NMS: suppress lower-confidence detection if it overlaps a better one
        var keptCount = 0
        var sumConf = 0f
        val safeRawCount = minOf(rawCount, rawPool.size)
        java.util.Arrays.fill(suppressed, 0, safeRawCount, false)
        for (i in 0 until rawCount) {
            if (suppressed[i]) continue
            val pi = rawPool[i]
            if (keptCount < keptPool.size) {
                val kd = keptPool[keptCount]
                kd.x = pi.x; kd.y = pi.y; kd.confidence = pi.confidence; kd.isUserTeam = pi.isUserTeam
                keptCount++
            }
            sumConf += pi.confidence
            for (j in i + 1 until rawCount) {
                if (suppressed[j]) continue
                val dx = abs(pi.x - rawPool[j].x)
                val dy = abs(pi.y - rawPool[j].y)
                val distSq = dx * dx + dy * dy
                if (distSq < 1600f) suppressed[j] = true
            }
        }

        val aggregateConfidence = if (keptCount == 0) 0f else (sumConf / keptCount).coerceIn(0f, 1f)
        resultList.bind(keptCount)

        return PlayerDetectionResult(
            detected = keptCount > 0,
            playerCount = keptCount,
            confidence = aggregateConfidence,
            detections = resultList
        )
    }
}
/* ======
PlayerDetector Anchor
====== */

/* ========
RuntimeConfidenceCalibrationResult
======== */

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

object BallDetector {

    private const val MIN_CANDIDATE_CONFIDENCE = 0.12f
    private const val COAST_FRAMES = 4  // PHASE4: 15fps — 6 frames=400ms coast too long; 4=266ms

    private var lastBallX = 0f
    private var lastBallY = 0f
    private var lastRadius = 0f
    private var lastConfidence = 0f

    private var initialized = false
    private var lostFrames = 0

    fun detect(
        candidate: BallCandidate?
    ): BallDetectionResult {

        if (candidate == null || candidate.score < MIN_CANDIDATE_CONFIDENCE) {
            lostFrames++
            if (lostFrames > COAST_FRAMES) {
                initialized = false
                lastBallX = 0f
                lastBallY = 0f
                lastRadius = 0f
                lastConfidence = 0f
                return BallDetectionResult(
                    detected = false,
                    x = 0f, y = 0f, radius = 0f, confidence = 0f,
                    searchPixels = 0, matchedPixels = 0
                )
            }
            if (initialized) {
                val coastFraction = 1f - (lostFrames.toFloat() / COAST_FRAMES)
                val coastConfidence = (lastConfidence * coastFraction).coerceIn(0f, 1f)
                return BallDetectionResult(
                    detected = true,
                    x = lastBallX, y = lastBallY, radius = lastRadius,
                    confidence = coastConfidence,
                    searchPixels = 0, matchedPixels = 0
                )
            }
            return BallDetectionResult(
                detected = false,
                x = 0f, y = 0f, radius = 0f, confidence = 0f,
                searchPixels = 0, matchedPixels = 0
            )
        }

        lostFrames = 0

        // PHASE4: 15fps EWA — ball moves more between frames, new position must dominate
        // Was 0.55(old)/0.45(new) → lagged badly at 15fps
        // Now 0.40(old)/0.60(new) → tracks fast movement, still smooths noise
        val filteredX =
            if (initialized)
                lastBallX * 0.40f + candidate.centerX * 0.60f
            else
                candidate.centerX

        val filteredY =
            if (initialized)
                lastBallY * 0.40f + candidate.centerY * 0.60f
            else
                candidate.centerY

        val filteredRadius =
            if (initialized)
                lastRadius * 0.45f + candidate.radius * 0.55f
            else
                candidate.radius

        val filteredConfidence =
            if (initialized)
                lastConfidence * 0.40f + candidate.score * 0.60f
            else
                candidate.score

        initialized = true

        lastBallX = filteredX
        lastBallY = filteredY
        lastRadius = filteredRadius
        lastConfidence = filteredConfidence

        val pixelArea = (candidate.pixelCount).coerceAtLeast(1)

        return BallDetectionResult(
            detected = true,
            x = filteredX,
            y = filteredY,
            radius = filteredRadius,
            confidence = filteredConfidence.coerceIn(0f, 1f),
            searchPixels = pixelArea,
            matchedPixels = (pixelArea * filteredConfidence).toInt().coerceAtLeast(1)
        )
    }
}
/* ======
BallDetector Anchor
====== */

/* ========
BallOverlay
======== */

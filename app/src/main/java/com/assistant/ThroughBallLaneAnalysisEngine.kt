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

object TrainedDetectionEngine {

    enum class Status { UNINITIALIZED, MISSING_MODEL, LOAD_ERROR, LOADED, ACTIVE, DISABLED }

    private const val MODEL_ASSET = "splendor_detector.tflite"
    private const val INPUT_SIZE = 320
    private const val MAX_DETECTIONS = 25
    private const val VALUES_PER_DETECTION = 6
    private const val MIN_BALL_SCORE = 0.35f
    private const val MAX_CONSECUTIVE_FAILURES = 5
    private const val DIVERGENCE_PX = 80f
    private const val PROOF_LOG_EVERY = 300L

    @Volatile private var status = Status.UNINITIALIZED
    @Volatile private var interpreter: Interpreter? = null

    private val inferences = AtomicLong(0L)
    private val failures = AtomicLong(0L)
    private val ballsFound = AtomicLong(0L)
    private val divergences = AtomicLong(0L)
    @Volatile private var consecutiveFailures = 0
    @Volatile private var lastLatencyMs = 0L
    @Volatile private var lastError = "none"

    // Preallocated once - the inference hot path allocates nothing.
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
    private val output =
        Array(1) { Array(MAX_DETECTIONS) { FloatArray(VALUES_PER_DETECTION) } }

    @Synchronized
    fun initialize(context: Context) {
        if (status != Status.UNINITIALIZED) return
        status = try {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            model.put(bytes)
            model.rewind()
            interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
            RuntimeLogger.log(
                "TFLITE model loaded (${bytes.size} bytes) - awaiting first real inference",
                "VISION"
            )
            Status.LOADED
        } catch (e: java.io.FileNotFoundException) {
            RuntimeLogger.log(
                "TFLITE model asset absent ($MODEL_ASSET) - heuristic pipeline unchanged",
                "VISION"
            )
            Status.MISSING_MODEL
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            RuntimeLogger.log("TFLITE model load failed: $lastError", "VISION")
            Status.LOAD_ERROR
        }
    }

    /*
     * Runs one real inference against the capture frame. Returns a ball
     * candidate in FRAME coordinates, or null when: no model, not confident,
     * buffer layout unexpected, or the engine disabled itself. Null always
     * means "heuristic owns this frame" - never a fabricated fallback.
     */
    fun ballCandidateOrNull(
        frame: FrameNormalizer.NormalizedFrame,
        heuristic: BallCandidate?
    ): BallCandidate? {
        val tflite = interpreter ?: return null
        if (status != Status.LOADED && status != Status.ACTIVE) return null

        val w = frame.width
        val h = frame.height
        val src = frame.buffer
        // Assumed RGBA_8888. If the buffer cannot hold that layout, stay
        // silent rather than reading garbage into the model.
        if (w <= 0 || h <= 0 || src.capacity() < w * h * 4) return null

        val start = System.nanoTime()
        return try {
            // Nearest-neighbor resample straight from the source buffer -
            // absolute get() so the shared buffer's position is untouched.
            // SPLENDOR_V14A_NATIVE_VISION_PREPROCESS_BEGIN
            val nativePreprocessOk: Boolean = if (com.assistant.NativeBridge.nativePreprocessAvailable) {
                try {
                    com.assistant.NativeBridge.nativePreprocessFrame(
                        src,
                        inputBuffer,
                        w,
                        h,
                        frame.rowStride,
                        INPUT_SIZE,
                        com.assistant.vision.CameraProfile.activeProfileId()
                    ) == 0
                } catch (_: Throwable) {
                    com.assistant.NativeBridge.markNativePreprocessUnavailable()
                    false
                }
            } else {
                false
            }
            
            if (nativePreprocessOk) {
                com.assistant.NativeBridge.logNativePreprocessProofOnce()
            }
            
            if (!nativePreprocessOk) {
                inputBuffer.rewind()
                var y = 0
                while (y < INPUT_SIZE) {
                    val srcY = y * h / INPUT_SIZE
                    var x = 0
                    while (x < INPUT_SIZE) {
                        val srcX = x * w / INPUT_SIZE
                        val idx = srcY * frame.rowStride + srcX * 4
                        inputBuffer.putFloat((src.get(idx).toInt() and 0xFF) / 255f)
                        inputBuffer.putFloat((src.get(idx + 1).toInt() and 0xFF) / 255f)
                        inputBuffer.putFloat((src.get(idx + 2).toInt() and 0xFF) / 255f)
                        x++
                    }
                    y++
                }
                inputBuffer.rewind()
            } else {
                inputBuffer.rewind()
            }
            // SPLENDOR_V14A_NATIVE_VISION_PREPROCESS_END

            tflite.run(inputBuffer, output)

            lastLatencyMs = (System.nanoTime() - start) / 1_000_000L
            val count = inferences.incrementAndGet()
            consecutiveFailures = 0

            if (status == Status.LOADED) {
                status = Status.ACTIVE
                RuntimeLogger.log(
                    "TFLITE ACTIVE: first real on-device inference completed in ${lastLatencyMs}ms",
                    "VISION"
                )
            }
            if (count % PROOF_LOG_EVERY == 0L) {
                RuntimeLogger.log(
                    "TFLITE_INFERENCE count=$count latency=${lastLatencyMs}ms " +
                        "balls=${ballsFound.get()} divergences=${divergences.get()}",
                    "VISION"
                )
            }

            // Best ball: class 0, highest score above threshold.
            var best: FloatArray? = null
            for (det in output[0]) {
                if (det[5].toInt() != 0) continue
                if (det[4] < MIN_BALL_SCORE) continue
                if (best == null || det[4] > best[4]) best = det
            }
            val chosen = best ?: return null

            val cx = (chosen[0] * w).coerceIn(0f, w.toFloat())
            val cy = (chosen[1] * h).coerceIn(0f, h.toFloat())
            val boxW = chosen[2] * w
            val boxH = chosen[3] * h
            val radius = (max(boxW, boxH) / 2f).coerceAtLeast(1f)

            ballsFound.incrementAndGet()

            // Cross-check against the heuristic - disagreement is counted
            // and periodically logged, never silently swallowed.
            if (heuristic != null &&
                hypot(cx - heuristic.centerX, cy - heuristic.centerY) > DIVERGENCE_PX
            ) {
                val d = divergences.incrementAndGet()
                if (d % 50L == 1L) {
                    RuntimeLogger.log(
                        "TFLITE_DIVERGENCE trained=($cx,$cy) heuristic=" +
                            "(${heuristic.centerX},${heuristic.centerY}) total=$d",
                        "VISION"
                    )
                }
            }

            BallCandidate(
                centerX = cx,
                centerY = cy,
                radius = radius,
                pixelCount = (boxW * boxH).toInt().coerceAtLeast(1),
                brightness = 0f, // not measured by the trained path - never faked
                score = chosen[4].coerceIn(0f, 1f)
            )
        } catch (e: Throwable) {
            failures.incrementAndGet()
            consecutiveFailures++
            lastError = e.message ?: e.javaClass.simpleName
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                status = Status.DISABLED
                try { interpreter?.close() } catch (_: Throwable) {}
                interpreter = null
                RuntimeLogger.log(
                    "TFLITE DISABLED after $consecutiveFailures consecutive failures " +
                        "(last: $lastError) - heuristic pipeline resumed as sole detector",
                    "VISION"
                )
            }
            null
        }
    }

    fun trainedRuntimeSnapshot(): Map<String, Any> = mapOf(
        "status" to status.name,
        "inferences" to inferences.get(),
        "failures" to failures.get(),
        "ballsFound" to ballsFound.get(),
        "divergences" to divergences.get(),
        "lastLatencyMs" to lastLatencyMs,
        "lastError" to lastError
    )
}
/* ======
TrainedDetectionEngine Anchor
====== */

/* ========
TrueCrossEngine
======== */

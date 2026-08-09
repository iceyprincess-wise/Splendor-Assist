package com.assistant.adapter.smartassist

import android.content.Context
import com.assistant.diagnostic.RuntimeLogger
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import kotlin.math.max

/*
 * TRAINED DETECTION ENGINE (Task C - detection upgrade path).
 *
 * HONEST STATUS CONTRACT - this engine never lies about what it is:
 *
 *   UNINITIALIZED -> initialize() not yet called.
 *   MISSING_MODEL -> no model asset on device. The heuristic pipeline runs
 *                    EXACTLY as before; this engine is a no-op.
 *   LOAD_ERROR    -> asset exists but failed to load; heuristic unchanged.
 *   LOADED        -> interpreter ready, ZERO inferences run yet. Still not
 *                    claimed as active.
 *   ACTIVE        -> at least one REAL on-device inference has completed
 *                    and is logged ("TFLITE ACTIVE"). Only this state may
 *                    ever be reported as "trained model running".
 *   DISABLED      -> too many consecutive inference failures; engine shut
 *                    itself off loudly and heuristic took over again.
 *
 * MODEL CONTRACT (for whoever supplies the asset):
 *   file      assets/splendor_detector.tflite
 *   input     [1, 320, 320, 3] float32 RGB, 0..1, nearest-neighbor resized
 *             from the capture frame (assumed RGBA_8888 byte layout)
 *   output    [1, 25, 6] float32: cxNorm, cyNorm, wNorm, hNorm, score, class
 *             class 0 = ball. Other classes reserved (1 player, 2 keeper).
 *
 * WIRING: VisionCore consults ballCandidateOrNull() BEFORE the heuristic
 * candidate is accepted. Trained wins only when ACTIVE and confident;
 * heuristic is computed every frame regardless, both as fallback and as a
 * cross-check - large disagreement is counted and logged, never hidden.
 *
 * PERFORMANCE (4GB device): input buffer and output arrays are
 * preallocated once - the per-frame inference path allocates nothing.
 */
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
            inputBuffer.rewind()
            var y = 0
            while (y < INPUT_SIZE) {
                val srcY = y * h / INPUT_SIZE
                var x = 0
                while (x < INPUT_SIZE) {
                    val srcX = x * w / INPUT_SIZE
                    val idx = (srcY * w + srcX) * 4
                    inputBuffer.putFloat((src.get(idx).toInt() and 0xFF) / 255f)
                    inputBuffer.putFloat((src.get(idx + 1).toInt() and 0xFF) / 255f)
                    inputBuffer.putFloat((src.get(idx + 2).toInt() and 0xFF) / 255f)
                    x++
                }
                y++
            }
            inputBuffer.rewind()

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

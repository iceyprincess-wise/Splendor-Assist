package com.assistant

import android.content.Context
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.storage.SplendorStorageRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import org.tensorflow.lite.Interpreter

/*
 * Trained detection engine — v3 TFLite detector (queue item 21).
 *
 * Model: mine-jgaan-3-yolo11m-t1 (Roboflow YOLO11m), float32 TFLite.
 * Licence: AGPL-3.0 (flagged by Roboflow on download — the model file
 * carries that licence wherever it ships; this engine does not relicence
 * it and the file is never bundled into the repo or the APK).
 * Test mAP@50 81.8% (Ball 46%, Buttons 75%, Keeper 87%, MyPlayer 98%,
 * OpponentPlayer 98%). Float32 ONLY — the float16 variant fails to
 * allocate (CONV_2D float16 input unsupported); never ship it.
 *
 * I/O contract (verified, harness-batch-tflite/investigation/04):
 *   input  [1, 640, 640, 3] float32 NHWC, 0..1 (letterboxed, grey-114 pad)
 *   output [1, 9, 8400] float32 — rows 0-3 xywh in 640-space, rows 4-8
 *   per-class scores (sigmoid already applied). Decode + NMS live in
 *   Yolo11Decoder (pure Kotlin); fusion rules in TrainedDetectionFusion.
 *   Class order is hard-coded in Yolo11Decoder (the .tflite carries zero
 *   metadata — ONNX metadata_props + .pt model.names pin it).
 *
 * SHIPPING — side-load (investigation/02): the 80 MB model is NOT in
 * the repo and NOT in the APK (the push-files route cannot carry an
 * 80 MB binary — hard transport block). initialize() reads it
 * file-first from /sdcard/Splendor-Assist/splendor_detector.tflite
 * (SplendorStorageRoot.file), verifies its SHA-256 against MODEL_SHA256
 * (pins v3 — change the constant with the model), then falls back to
 * the asset of the same name, then MISSING_MODEL. A SHA mismatch logs
 * LOAD_ERROR naming the mismatch and never loads the corrupt file.
 * With no file present the engine is MISSING_MODEL and behaviour is
 * IDENTICAL to the pure-heuristic pipeline — file presence is the real
 * opt-in, which is why VisionConfiguration.trainedDetectionEnabled
 * defaults TRUE.
 *
 * CADENCE — ASYNC, OFF the hot path. tflite.run() takes ~0.9 s on
 * sandbox CPU and an unknown time on the phone; running it inside
 * VisionCore.process() would hold the single-flight slot, drop every
 * intervening frame (VISION_BUSY_SKIP) and trip the 1 s stuck-vision
 * heal every frame (the heal cannot interrupt tflite.run() — zero
 * suspension points). So: VisionCore.submitFrame() copies the frame
 * into an engine-owned snapshot (at most one per INFERENCE_INTERVAL_MS
 * = 500 ms) and a dedicated Dispatchers.Default loop runs inference
 * back-to-back, publishing each result into a volatile holder stamped
 * with the source frame's capturedAtNanos + inferenceMs. VisionCore
 * reads the holder per frame; the heuristic still runs every frame and
 * owns every frame. The freshness gate (FRESH_MS = 250) is the
 * slowness protection: a slow result is stale by the time it lands and
 * is ignored, degrading gracefully to heuristic-only.
 *
 * BUFFER OWNERSHIP: the capture plane copy (OverlayService) lives in a
 * REUSED direct ByteBuffer — the next admitted frame overwrites it —
 * so the inference loop must NEVER read the vision buffer directly.
 * submitFrame() copies the bytes into a fresh direct ByteBuffer owned
 * by the loop (single-flight guarantees the source is stable during
 * the copy). No torn reads: the loop only ever reads its own snapshot.
 *
 * PREPROCESS — letterbox (D2 wins: harness-batch-tflite/d2 experiment).
 * Stretch distorts x by 2.22x at 288x640 and the model answers with
 * false Keepers, a phantom Ball and lost players; letterbox reproduces
 * the .pt run exactly. The native path only does stretch, so it is
 * BYPASSED for v3 input (NATIVE_STRETCH_ENABLED = false below).
 * Extending the C code to letterbox is a follow-up, not this trial.
 * The bypassed native block is RETAINED (not deleted): its Q68-95
 * failure handling is pinned by harness g25s, and the C function still
 * exists — a future letterbox-capable native path re-enables it with
 * one const flip. The gate is a runtime const check, so the code stays
 * compiled and the pins stay live.
 *
 * HONEST STATUS CONTRACT (moved here by Phase T, T-13 — it used to sit
 * as an orphan block at the end of ThroughBallLaneAnalysisEngine.kt):
 * this engine never lies about what it is:
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
 */
object TrainedDetectionEngine {

    enum class Status { UNINITIALIZED, MISSING_MODEL, LOAD_ERROR, LOADED, ACTIVE, DISABLED }

    private const val MODEL_ASSET = "splendor_detector.tflite"
    private const val MODEL_FILE_NAME = "splendor_detector.tflite"
    // Pins v3: SHA-256 of mine-jgaan-3-yolo11m-t1_float32.tflite
    // (80,408,848 bytes). MUST change with the model — a file whose hash
    // does not match is never loaded.
    private const val MODEL_SHA256 =
        "f04bb9a3c8bf1cd1b400935eaa90deda43daf87d9c45d4f2b292641c1b8c30ff"
    private const val INPUT_SIZE = 640
    private const val MIN_BALL_SCORE = 0.35f
    private const val MAX_CONSECUTIVE_FAILURES = 5
    private const val PROOF_LOG_EVERY = 300L

    // D2: the native preprocess path only does a stretch resample, and
    // stretch loses to letterbox for the v3 model (false Keepers, a
    // phantom Ball, lost players). Bypassed for v3 input; the block is
    // retained (see header) for a future C letterbox extension.
    private const val NATIVE_STRETCH_ENABLED = false

    // D4 cadence (STATED TUNING): at most one inference submission per
    // 500 ms; the loop itself runs back-to-back on whatever is latest.
    private const val INFERENCE_INTERVAL_MS = 500L

    @Volatile private var status = Status.UNINITIALIZED
    @Volatile private var interpreter: Interpreter? = null

    private val inferences = AtomicLong(0L)
    private val ballsFound = AtomicLong(0L)
    private val divergences = AtomicLong(0L)
    // Q68-95 (batch 5b): native-preprocess failure counters — the C return
    // codes were ignored before, so failures were invisible.
    private val preprocessFailures = AtomicLong(0L)
    @Volatile private var lastPreprocessCode = 0
    @Volatile private var consecutiveFailures = 0
    @Volatile private var lastLatencyMs = 0L
    @Volatile private var lastError = "none"
    @Volatile private var lastSubmitMs = 0L

    // Preallocated once — the inference path allocates nothing except the
    // per-submit frame snapshot (see submitFrame).
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
    private val output =
        Array(1) { Array(Yolo11Decoder.OUTPUT_ROWS) { FloatArray(Yolo11Decoder.CANDIDATE_COUNT) } }

    // Latest async inference result (volatile holder). Detections are in
    // the SOURCE frame's capture coordinates; consumers must check the
    // geometry + freshness before using them (fusedBall/fusedKeeper/
    // mergedPlayers do that — read through them, not this field).
    data class TrainedInference(
        val detections: List<Yolo11Decoder.YoloDetection>,
        val capturedAtNanos: Long,
        val inferenceMs: Long,
        val frameWidth: Int,
        val frameHeight: Int
    )

    @Volatile private var latest: TrainedInference? = null

    // One engine-owned snapshot per submit; the loop owns the buffer
    // from here on (never the vision buffer — see header).
    private data class FrameSnapshot(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val pixelStride: Int,
        val capturedAtNanos: Long
    )

    private val frameChannel = Channel<FrameSnapshot>(Channel.CONFLATED)
    private val inferenceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val loopStarted = AtomicBoolean(false)

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun buildInterpreter(bytes: ByteArray, source: String): Boolean {
        return try {
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            model.put(bytes)
            model.rewind()
            val created = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
            // Shape check at load (never assume): the v3 contract is
            // [1,640,640,3] in, [1,9,8400] out. A mismatch is LOAD_ERROR.
            val inShape = created.getInputTensor(0).shape()
            val outShape = created.getOutputTensor(0).shape()
            if (!inShape.contentEquals(intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3)) ||
                !outShape.contentEquals(intArrayOf(1, Yolo11Decoder.OUTPUT_ROWS, Yolo11Decoder.CANDIDATE_COUNT))
            ) {
                lastError = "tensor shape mismatch: in=${inShape.contentToString()} " +
                    "out=${outShape.contentToString()} (expected [1,640,640,3] / [1,9,8400])"
                try { created.close() } catch (_: Throwable) {}
                RuntimeLogger.log("TFLITE model load failed ($source): $lastError", "VISION")
                false
            } else {
                interpreter = created
                RuntimeLogger.log(
                    "TFLITE model loaded from $source (${bytes.size} bytes) - awaiting first real inference",
                    "VISION"
                )
                true
            }
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            RuntimeLogger.log("TFLITE model load failed ($source): $lastError", "VISION")
            false
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (status != Status.UNINITIALIZED) return
        var loaded = false
        var sideLoadFailed = false
        try {
            // File-first: side-load from the storage root. Storage may
            // be unready (permission) — file() throws then, and the
            // asset fallback below is the correct next step, not a crash.
            try {
                val sideFile = SplendorStorageRoot.file(MODEL_FILE_NAME)
                if (sideFile.isFile && sideFile.canRead()) {
                    val bytes = sideFile.readBytes()
                    val actualSha = sha256Hex(bytes)
                    if (actualSha != MODEL_SHA256) {
                        sideLoadFailed = true
                        lastError = "SHA mismatch: expected=$MODEL_SHA256 actual=$actualSha"
                        RuntimeLogger.log(
                            "TFLITE LOAD_ERROR: side-loaded model SHA mismatch " +
                                "(expected $MODEL_SHA256, got $actualSha) — corrupt file ignored, trying asset fallback",
                            "VISION"
                        )
                    } else if (buildInterpreter(bytes, "side-load ${sideFile.absolutePath}")) {
                        loaded = true
                    } else {
                        sideLoadFailed = true
                    }
                }
            } catch (_: Throwable) {
                // Storage unready / unreadable: asset fallback below.
            }
            if (!loaded) {
                try {
                    val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                    if (buildInterpreter(bytes, "asset $MODEL_ASSET")) {
                        loaded = true
                    } else {
                        sideLoadFailed = true
                    }
                } catch (e: java.io.FileNotFoundException) {
                    if (!sideLoadFailed) {
                        RuntimeLogger.log(
                            "TFLITE model absent (no side-load file, no $MODEL_ASSET asset) - heuristic pipeline unchanged",
                            "VISION"
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            RuntimeLogger.log("TFLITE model load failed: $lastError", "VISION")
            status = Status.LOAD_ERROR
            return
        }
        status = when {
            loaded -> {
                startInferenceLoop()
                Status.LOADED
            }
            sideLoadFailed -> Status.LOAD_ERROR
            else -> Status.MISSING_MODEL
        }
    }

    private fun startInferenceLoop() {
        if (!loopStarted.compareAndSet(false, true)) return
        inferenceScope.launch {
            while (true) {
                val snap = try {
                    frameChannel.receive()
                } catch (e: Throwable) {
                    // Channel closed (never happens — it is never closed)
                    // ends the loop; any other failure drops one frame.
                    if (e is kotlinx.coroutines.channels.ClosedReceiveChannelException) break
                    continue
                }
                runInference(snap)
            }
        }
    }

    /*
     * Offer the latest frame to the async inference loop. Called from
     * VisionCore.process() every frame; cheap by design — a volatile
     * status check + a 500 ms cadence gate, then one direct-buffer copy
     * (~737 KB at 288x640) only when due. The copy runs while the vision
     * coroutine owns the buffer (single-flight), so the source is
     * stable; the loop only ever reads the copy. No-op when the engine
     * is not loaded, when the gate is off (the caller checks the flag),
     * or when a submit already happened inside the interval.
     */
    fun submitFrame(frame: FrameNormalizer.NormalizedFrame) {
        if (status != Status.LOADED && status != Status.ACTIVE) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSubmitMs < INFERENCE_INTERVAL_MS) return
        val w = frame.width
        val h = frame.height
        val src = frame.buffer
        // Assumed RGBA_8888. If the buffer cannot hold that layout, stay
        // silent rather than copying garbage into the model.
        if (w <= 0 || h <= 0 || frame.pixelStride < 3 || frame.rowStride.toLong() < w.toLong() * frame.pixelStride ||
            (h - 1L) * frame.rowStride + (w - 1L) * frame.pixelStride + 3 > src.limit()
        ) return
        lastSubmitMs = nowMs
        try {
            val size = src.limit()
            val copy = ByteBuffer.allocateDirect(if (size > 0) size else 1)
            val dup = src.duplicate()
            dup.position(0)
            dup.limit(size)
            copy.put(dup)
            copy.flip()
            // Conflated channel: latest wins; a pending frame is replaced,
            // never queued behind a stale one.
            frameChannel.trySend(
                FrameSnapshot(copy, w, h, frame.rowStride, frame.pixelStride, frame.metadata.timestampNanos)
            )
        } catch (_: Throwable) {
            // A failed snapshot must never break vision: the holder
            // keeps its last good value until it goes stale, and the
            // next due submit retries. No log here — this runs on the
            // hot path and a repeated allocation failure would spam.
        }
    }

    // Letterbox resample (D2 winner): scale = min(640/w, 640/h), grey-114
    // pad, nearest-neighbor — the same sampling family the old stretch
    // fallback used, so the only change is the aspect handling. Reads
    // the snapshot buffer with absolute get() (its position untouched).
    private fun resampleLetterbox(snap: FrameSnapshot) {
        val geometry = Yolo11Decoder.letterboxGeometry(snap.width, snap.height, INPUT_SIZE)
        val src = snap.buffer
        val padFloat = Yolo11Decoder.LETTERBOX_PAD / 255f
        inputBuffer.rewind()
        var y = 0
        while (y < INPUT_SIZE) {
            val srcY = ((y - geometry.padY) / geometry.scale).toInt()
            val inRow = srcY in 0 until snap.height
            var x = 0
            while (x < INPUT_SIZE) {
                val srcX = ((x - geometry.padX) / geometry.scale).toInt()
                if (inRow && srcX in 0 until snap.width) {
                    val idx = srcY * snap.rowStride + srcX * snap.pixelStride
                    inputBuffer.putFloat((src.get(idx).toInt() and 0xFF) / 255f)
                    inputBuffer.putFloat((src.get(idx + 1).toInt() and 0xFF) / 255f)
                    inputBuffer.putFloat((src.get(idx + 2).toInt() and 0xFF) / 255f)
                } else {
                    inputBuffer.putFloat(padFloat)
                    inputBuffer.putFloat(padFloat)
                    inputBuffer.putFloat(padFloat)
                }
                x++
            }
            y++
        }
        inputBuffer.rewind()
    }

    // BYPASSED for v3 input (NATIVE_STRETCH_ENABLED = false): the native
    // preprocess only does a stretch resample, and stretch loses to
    // letterbox for this model (D2). Retained — not deleted — because
    // its Q68-95 failure handling is pinned by harness g25s and the C
    // function still exists; a future C letterbox extension re-enables
    // it with one const flip. Do not call for v3 input while the const
    // is false.
    private fun resampleStretchNative(snap: FrameSnapshot) {
        // SPLENDOR_V14A_NATIVE_VISION_PREPROCESS_BEGIN
        val nativePreprocessOk: Boolean = if (snap.pixelStride == 4 && com.assistant.NativeBridge.nativePreprocessAvailable) {
            try {
                val preprocessCode = com.assistant.NativeBridge.nativePreprocessFrame(
                    snap.buffer,
                    inputBuffer,
                    snap.width,
                    snap.height,
                    snap.rowStride,
                    INPUT_SIZE,
                    com.assistant.vision.CameraProfile.activeProfileId()
                )
                if (preprocessCode == 0) true else {
                    // Q68-95 (batch 5b): the C return codes were ignored —
                    // every nonzero silently fell back to the Kotlin
                    // resample with no log, and nativePreprocessAvailable
                    // stayed true, so every frame paid a failed JNI call.
                    // Codes (native_vision_preprocessor.c): 0 ok; -1 null
                    // env/src/dst; -2 not direct buffer; -3 bad geometry;
                    // -6 negative capacity; -7 src too small; -8 dst too
                    // small. PERMANENT codes (-1/-2/-6/-8 — buffers and
                    // geometry are fixed by construction) mark the native
                    // path unavailable exactly like the Throwable path.
                    // GEOMETRY codes (-3/-7 — vary per frame and rotation)
                    // log only; the next frame may succeed.
                    preprocessFailures.incrementAndGet()
                    if (preprocessCode != lastPreprocessCode || preprocessFailures.get() % PROOF_LOG_EVERY == 0L) {
                        lastPreprocessCode = preprocessCode
                        try {
                            com.assistant.diagnostic.RuntimeLogger.log(
                                "PREPROCESS_NATIVE_CODE=$preprocessCode failures=${preprocessFailures.get()} (w=${snap.width} h=${snap.height} rowStride=${snap.rowStride})",
                                "NATIVE_VISION"
                            )
                        } catch (_: Throwable) {}
                    }
                    if (preprocessCode == -1 || preprocessCode == -2 || preprocessCode == -6 || preprocessCode == -8) {
                        com.assistant.NativeBridge.markNativePreprocessUnavailable()
                    }
                    false
                }
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
            val src = snap.buffer
            var y = 0
            while (y < INPUT_SIZE) {
                val srcY = y * snap.height / INPUT_SIZE
                var x = 0
                while (x < INPUT_SIZE) {
                    val srcX = x * snap.width / INPUT_SIZE
                    val idx = srcY * snap.rowStride + srcX * snap.pixelStride
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
    }

    // One async inference on an engine-owned snapshot. Runs on the
    // dedicated loop scope — never on the vision hot path. A failure
    // here degrades to heuristic-only (the holder keeps its last good
    // value until it goes stale); 5 consecutive failures disable the
    // engine exactly as before.
    private fun runInference(snap: FrameSnapshot) {
        val tflite = interpreter ?: return
        if (status != Status.LOADED && status != Status.ACTIVE) return
        val start = System.nanoTime()
        try {
            if (NATIVE_STRETCH_ENABLED) {
                resampleStretchNative(snap)
            } else {
                resampleLetterbox(snap)
            }

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

            val geometry = Yolo11Decoder.letterboxGeometry(snap.width, snap.height, INPUT_SIZE)
            val detections = Yolo11Decoder.decode(output[0], snap.width, snap.height, geometry)
            latest = TrainedInference(
                detections = detections,
                capturedAtNanos = snap.capturedAtNanos,
                inferenceMs = lastLatencyMs,
                frameWidth = snap.width,
                frameHeight = snap.height
            )

            val bestBall = TrainedDetectionFusion.bestBall(detections)
            if (bestBall != null && bestBall.confidence >= MIN_BALL_SCORE) ballsFound.incrementAndGet()

            if (count % PROOF_LOG_EVERY == 0L) {
                RuntimeLogger.log(
                    "TFLITE_INFERENCE count=$count latency=${lastLatencyMs}ms " +
                        "balls=${ballsFound.get()} divergences=${divergences.get()}",
                    "VISION"
                )
                // Buttons are LOG-ONLY (no consumer exists): a throttled
                // line with count + best box, for offline validation of
                // the static button table. Never wired into decisions.
                val buttons = detections.filter { it.classId == Yolo11Decoder.CLASS_BUTTONS }
                if (buttons.isNotEmpty()) {
                    val best = buttons.maxByOrNull { it.confidence }!!
                    RuntimeLogger.log(
                        "TFLITE_BUTTONS count=${buttons.size} best=(${best.centerX},${best.centerY}) conf=${best.confidence}",
                        "VISION"
                    )
                }
            }
        } catch (e: Throwable) {
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
        }
    }

    // Divergence counter: the fusion (VisionCore-time) reports a fresh
    // trained ball that disagreed with the heuristic by > 80 px. The
    // heuristic wins that frame; the count + throttled log keep the
    // disagreement visible instead of silent.
    private fun noteDivergence() {
        val d = divergences.incrementAndGet()
        if (d % 50L == 1L) {
            RuntimeLogger.log(
                "TFLITE_DIVERGENCE trained ball disagreed with heuristic by >80px; heuristic kept total=$d",
                "VISION"
            )
        }
    }

    /*
     * Fusion reads (VisionCore-time). Each returns null when the trained
     * result must NOT win — no model, stale result, weak confidence, or
     * a geometry change since the inference ran — and the caller falls
     * back to the heuristic it already computed. Null always means
     * "heuristic owns this frame" — never a fabricated fallback.
     */
    fun fusedBall(
        heuristic: BallCandidate?,
        frameWidth: Int,
        frameHeight: Int,
        frameTimestampNanos: Long
    ): BallCandidate? {
        val snap = latest ?: return null
        if (snap.frameWidth != frameWidth || snap.frameHeight != frameHeight) return null
        val ageMs = TrainedDetectionFusion.ageMs(frameTimestampNanos, snap.capturedAtNanos)
        val fusion = TrainedDetectionFusion.selectBall(
            TrainedDetectionFusion.bestBall(snap.detections), heuristic, ageMs
        )
        if (fusion.divergent) noteDivergence()
        return fusion.candidate
    }

    fun fusedKeeper(
        frameWidth: Int,
        frameHeight: Int,
        frameTimestampNanos: Long
    ): GoalkeeperDetectionResult? {
        val snap = latest ?: return null
        if (snap.frameWidth != frameWidth || snap.frameHeight != frameHeight) return null
        val ageMs = TrainedDetectionFusion.ageMs(frameTimestampNanos, snap.capturedAtNanos)
        return TrainedDetectionFusion.selectKeeper(
            TrainedDetectionFusion.bestKeeper(snap.detections), ageMs
        )
    }

    fun mergedPlayers(
        heuristic: PlayerDetectionResult,
        frameWidth: Int,
        frameHeight: Int,
        frameTimestampNanos: Long
    ): PlayerDetectionResult {
        val snap = latest ?: return heuristic
        if (snap.frameWidth != frameWidth || snap.frameHeight != frameHeight) return heuristic
        val ageMs = TrainedDetectionFusion.ageMs(frameTimestampNanos, snap.capturedAtNanos)
        return TrainedDetectionFusion.mergePlayers(
            TrainedDetectionFusion.playerDetections(snap.detections), heuristic, ageMs
        )
    }
}

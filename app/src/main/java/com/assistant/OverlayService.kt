package com.assistant

// F49_PATCH_V1

import android.annotation.SuppressLint
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.RuntimeMetricsRegistry
import com.assistant.SmartAssistRepository
import com.assistant.survival.OverlaySurvivalEngine
import com.assistant.overlay.notification.RuntimeNotificationCoordinator
import com.assistant.render.ChoreographerRenderLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay

import android.media.ImageReader
import java.nio.ByteBuffer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.widget.TextView
import com.assistant.CallOverlayRepository
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import com.assistant.ModMenuController

class OverlayService : Service(), ComponentCallbacks2 {
    private var lastCaptureFaultLog = 0L

    enum class CaptureState {
        IDLE,
        AUTHORIZED,
        ACTIVE,
        REVOKED,
        FAILED
    }

    enum class CaptureResult { SUCCESS, BUSY, INVALID_SESSION, FAILED }

    @Volatile
    private var runtimeInitialized = false

    @android.annotation.SuppressLint("StaticFieldLeak")
    companion object {
        private const val CHANNEL_ID = "efootball_assistant_channel"
        private const val NOTIFICATION_ID = 101

        @Volatile var instance: OverlayService? = null
            private set

        @JvmStatic
        fun restartCaptureIfAlive(): Boolean =
            instance?.restartCapture() ?: false

        @JvmStatic
        fun projectionRevoked(): Boolean =
            instance?.isProjectionRevoked ?: false

        @JvmStatic
        fun projectionIdle(): Boolean =
            instance?.isProjectionIdle ?: true

        @JvmStatic
        fun projectionFailed(): Boolean =
            instance?.isProjectionFailed ?: false

        @JvmStatic
        fun captureState(): CaptureState =
            instance?.readCaptureState() ?: CaptureState.IDLE

        @JvmStatic
        fun requestRecoveryPrompt() {
            instance?.showCaptureRecoveryPrompt()
        }

        // SPLENDOR_V42_AUTOHEAL_STATIC_BEGIN
        // Named requestAutoHeal() (not autoHealCapture()) to avoid JVM signature
        // which would collide with the instance fun autoHealCapture() at class level.
        @JvmStatic
        fun requestAutoHeal(): Boolean =
            instance?.autoHealCapture() ?: false
        // SPLENDOR_V42_AUTOHEAL_STATIC_END
    }

    @Volatile private var isRunning = false
    private var modMenuController: ModMenuController? = null
    private var interceptionBridge: com.assistant.overlay.bridge.InterceptionControlRoomBridge? = null
    private var processingThread: Thread? = null
    @Volatile private var reusableVisionBuffer: java.nio.ByteBuffer? = null
    // N4: generation of the frame bytes currently held in
    // reusableVisionBuffer. The listener allocates a FRESH buffer (never
    // reuses the shared one) when the generation has advanced since the
    // last copy, so an abandoned coroutine returning late can never read
    // bytes overwritten by a newer frame.
    @Volatile private var visionBufferGeneration = 0
    private var previousVisionLayout: Triple<Int, Int, Int>? = null
    @Volatile private var previousVisionBuffer: java.nio.ByteBuffer? = null
    // SPLENDOR_P6_DEDUP_DIAG_FIELDS_BEGIN
    // Duplicate-frame early-return counter for LOOP_FROZEN triage.
    @Volatile private var duplicateFrameCount = 0L
    @Volatile private var lastDuplicateLogMs = 0L
    // F37-D6: throttled counter/log for vision-busy frame drops (see below).
    @Volatile private var visionBusySkips = 0L
    @Volatile private var lastVisionBusyLogMs = 0L
    // SPLENDOR_P6_DEDUP_DIAG_FIELDS_END
    private val emptyVisionBuffer: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(0)
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var txtEngineStatus: TextView
    private lateinit var notificationManager: NotificationManager
    private var panicIndicator: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    // SPLENDOR_V42_AUTOHEAL_FIELDS_BEGIN
    // Auto-heal capture: keep the granted token so recovery can be attempted
    // without user interaction (token reuse) before falling back to the
    // system consent dialog (one tap — Android 14 requires one consent per
    // capture session).
    @Volatile private var savedProjectionCode = 0
    @Volatile private var savedProjectionData: Intent? = null
    @Volatile private var lastAutoHealAttemptMs = 0L
    // SPLENDOR_V42_AUTOHEAL_FIELDS_END

    private var captureState: CaptureState = CaptureState.IDLE
    private val captureLock = ReentrantLock()
    private val visionScope = CoroutineScope(Dispatchers.Default + Job())

    private fun readCaptureState(): CaptureState {
        captureLock.lock()
        try {
            return captureState
        } finally {
            captureLock.unlock()
        }
    }

    val isProjectionRevoked: Boolean
        get() = readCaptureState() == CaptureState.REVOKED

    val isProjectionIdle: Boolean
        get() = readCaptureState() == CaptureState.IDLE

    val isProjectionFailed: Boolean
        get() = readCaptureState() == CaptureState.FAILED

    private var ocrIoThread: android.os.HandlerThread? = null
    private var ocrIoHandler: android.os.Handler? = null
    private var lastOcrTime = 0L
    // SPLENDOR_P12_T4_OCR_SPAM: the last OCR text logged, so identical
    // text is not re-logged on every 1.5s OCR tick.
    @Volatile private var lastLoggedOcrText = ""
    private var lastMatchDetectionTime = 0L
    private val OCR_INTERVAL_MS = 1500L
    private var reusableBitmap: Bitmap? = null
    private val taskExecutionLock = ReentrantLock()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile private var lastFrameProcessedMs = 0L
    private val visionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    // N4: frame generation. Bumped only by abandonWedgedVision(). A
    // coroutine captures its generation at launch; if it returns after an
    // abandon, its finally must NOT clear the new frame's flag and its
    // late stages must NOT write shared state or dispatch.
    private val visionGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    // N4: per-stage heartbeat of the in-flight vision coroutine, so the
    // next wedge names the blocking stage instead of going silent.
    @Volatile private var visionStage = "idle"
    @Volatile private var visionStageStartedMs = 0L

    // V53: Hardware-level locks to prevent HyperOS CPU/Network suspension
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // V51: Vision Watchdog to prevent LOOP_FROZEN from coroutine death
    private var visionStartTimeMs = 0L

    // No68-C-D18: one-shot arm for the stuck-vision heal (see the
    // VISION_BUSY branch in imageAvailableListener). Reset in the vision
    // coroutine's finally block when the in-flight frame completes.
    @Volatile private var visionStuckHealArmed = false

    // N7: throttle for the CAPTURE GATE OVERRIDE witness log (below).
    @Volatile private var lastGateOverrideLogMs = 0L

    private val captureFrameIntervalMs: Long
        get() {
            val memory = com.assistant.MemoryCaptureGateEngine.recommendedIntervalMs()
            // No68-C-D4: LoadShedCaptureBrakeEngine.recommendedIntervalMs()
            // used to have zero callers — the load-shed brake published to
            // the bus and stopped there, so LIGHT (15 fps) / HEAVY (10 fps)
            // never throttled capture despite the engine's KDoc. Take the
            // slower of the two gates, but never let a brake starve the
            // decision loop in-game: the V10 latency principle floors
            // in-game capture at 30 fps (33 ms). The brake still sheds
            // (16 ms -> 33 ms at target 60) but cannot drop the game-time
            // decision rate below 30 fps. Out of game the full brake
            // applies (HEAVY 100 ms, LIGHT 66 ms).
            val brake = try { com.assistant.LoadShedCaptureBrakeEngine.recommendedIntervalMs() } catch (_: Throwable) { 0L }
            val combined = if (brake > 0L) maxOf(memory, brake) else memory
            // N7: in-game the memory brake is capped to 33 ms (and the gate
            // itself to 16 ms at target 60 fps), so a CRITICAL tier changes
            // nothing here. Say so, throttled, instead of letting the gate
            // log claim a reduction that never happened. In-game only: out
            // of game the memory interval applies in full, so there is no
            // override to witness.
            if (com.assistant.MemoryCaptureGateEngine.captureThrottle >= 3) {
                val inGame = try { com.assistant.VisionTrust.isGameForeground() } catch (_: Throwable) { false }
                if (inGame) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastGateOverrideLogMs > 30_000L) {
                        lastGateOverrideLogMs = nowMs
                        try { com.assistant.diagnostic.RuntimeLogger.log(
                            "CAPTURE GATE OVERRIDE: memory CRITICAL (throttle=3) but in-game interval stays ${combined.coerceAtMost(33L)}ms (memory asked ${memory}ms)",
                            "MEMORY"
                        ) } catch (_: Throwable) {}
                    }
                }
            }
            // P3: off-match rate adapter — lowers the processed-frame
            // interval to 200 ms ONLY while the game is not foreground,
            // and restores the full in-game interval otherwise. Ball
            // absence NEVER lowers in-game (batch 5b fix): v219 showed
            // ballTrust=0 in-game lowering the rate to 200 ms, which
            // starved the very detection that would restore the ball
            // (ball unseen -> slower -> ball stays unseen). The in-game
            // full-rate path below is unchanged (combined, capped at
            // 33 ms); D1 holds — memory still cannot slow in-game
            // capture, the adapter only sheds off-match work. Transitions
            // are logged (rare by construction: re-lowering needs the
            // game to leave the foreground).
            return try {
                val wasLowered = com.assistant.OffMatchRateAdapter.isLowered()
                val interval = com.assistant.OffMatchRateAdapter.intervalMs(
                    com.assistant.VisionTrust.isGameForeground(),
                    combined
                )
                if (com.assistant.OffMatchRateAdapter.isLowered() != wasLowered) {
                    try { com.assistant.diagnostic.RuntimeLogger.log(
                        "OFFMATCH_RATE lowered=${com.assistant.OffMatchRateAdapter.isLowered()} intervalMs=$interval (gameForeground=${com.assistant.VisionTrust.isGameForeground()} ballTrust=${com.assistant.VisionTrust.ballTrust()})",
                        "OVERLAY"
                    ) } catch (_: Throwable) {}
                }
                interval
            } catch (_: Throwable) { combined }
        }

    @Volatile private var captureFrameCount = 0L
    private val trajectoryHandler = Handler(Looper.getMainLooper())
    private var trajectoryRunnable: Runnable? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var keepAliveHandler = Handler(Looper.getMainLooper())
    private var keepAliveRunnable: Runnable? = null
    @Volatile private var recoveryPromptShown = false
    private var recoveryPromptView: TextView? = null
    private var lastPanicState: Boolean? = null

    private fun teardownCaptureResources(finalState: CaptureState = CaptureState.IDLE) {
        captureLock.lock()
        try {
            teardownCaptureResourcesInternal(finalState)
        } finally {
            captureLock.unlock()
        }
    }

    private fun teardownCaptureResourcesInternal(finalState: CaptureState = CaptureState.IDLE) {
        com.assistant.PerformanceHintEngine.endCapture()
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        try {
            val cb = projectionCallback
            val mp = mediaProjection
            if (cb != null && mp != null) {
                mp.unregisterCallback(cb)
            }
        } catch (_: Throwable) {}
        projectionCallback = null
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        lastFrameProcessedMs = 0L
        captureFrameCount = 0L
        // No68-C-D17: the dedup/busy counters used to survive teardown, so
        // a fresh capture session inherited the previous session's counts
        // and the throttled logs read as one long session.
        duplicateFrameCount = 0L
        visionBusySkips = 0L
        lastDuplicateLogMs = 0L
        lastVisionBusyLogMs = 0L
        visionStuckHealArmed = false
        captureState = finalState
        // f5: capture teardown must not leave the mod menu on screen.
        modMenuController?.hideLauncher()
        modMenuController?.hide()
    }

    private var currentWidth = 0
    private var currentHeight = 0
    private var currentDpi = 0

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null } ?: return@OnImageAvailableListener
        com.assistant.CrashForensics.mark(2, 1, captureFrameCount)
        com.assistant.DiagnosticSamplers.onCaptureFrame(System.nanoTime())
        com.assistant.CrashForensics.mark(5, 4, captureFrameCount)
        val acquiredAtNanos = System.nanoTime()
        // No68-C-D15: capturedAtNanos used to be the acquire time, so the
        // pipeline latency FrameAssembler measures excluded the time the
        // frame already spent queued in the ImageReader (up to a full
        // frame at 60 fps, more under load). Image.getTimestamp() is the
        // compositor's timestamp for this frame in the same monotonic base
        // as System.nanoTime(); use it when it is sane (within the last
        // second, not in the future) and fall back to the acquire time.
        val capturedAtNanos = try {
            val ts = image.timestamp
            if (ts in (acquiredAtNanos - 1_000_000_000L)..acquiredAtNanos) ts else acquiredAtNanos
        } catch (_: Throwable) { acquiredAtNanos }
        val captureNow = System.currentTimeMillis()
        if (captureNow - lastFrameProcessedMs < captureFrameIntervalMs) {
            image.close()
            return@OnImageAvailableListener
        }
        lastFrameProcessedMs = captureNow
        captureFrameCount++

        com.assistant.SplendorCaptureRecovery.markFrame()
        // No62-D18: overlay liveness heartbeat — keeps
        // OverlaySurvivalRegistry ACTIVE while capture delivers frames.
        OverlaySurvivalEngine.heartbeat()

        if (com.assistant.vision.ForegroundGate.shouldSkipCapture()) {
            image.close()
            return@OnImageAvailableListener
        }

        // OMEGA FIX: SYNCHRONOUS EXTRACTION prevents "Image is already closed" race conditions
        val width = try { image.width } catch (_: Throwable) { try { image.close() } catch (_: Throwable) {}; return@OnImageAvailableListener }
        val height = try { image.height } catch (_: Throwable) { try { image.close() } catch (_: Throwable) {}; return@OnImageAvailableListener }
        com.assistant.CrashForensics.setCaptureSize(width, height)
        com.assistant.CrashForensics.mark(7, 1, (width.toLong() shl 32) or (height.toLong() and 0xffffffffL))
        val plane = try { image.planes[0] } catch (_: Throwable) { try { image.close() } catch (_: Throwable) {}; return@OnImageAvailableListener }
        // SPLENDOR_P9_OS_PLANE_GUARD: same close-and-return guard as
        // width/height/planes[0] above -- an "Image is already closed"
        // fault here would otherwise escape onImageAvailable on the OCR
        // HandlerThread and silently stop all future frames.
        val rowStride = try { plane.rowStride } catch (_: Throwable) { try { image.close() } catch (_: Throwable) {}; return@OnImageAvailableListener }
        val pixelStride = try { plane.pixelStride } catch (_: Throwable) { try { image.close() } catch (_: Throwable) {}; return@OnImageAvailableListener }
        val originalBuffer = try { plane.buffer } catch (_: Throwable) { try { image.close() } catch (_: Throwable) {}; return@OnImageAvailableListener }
        
        // SPLENDOR_V23B_VISION_BUFFER_REUSE_BEGIN
        val startVision = visionInFlight.compareAndSet(false, true)

            if (startVision) {
                visionStartTimeMs = System.currentTimeMillis()
                // REMOVED: 500ms Watchdog caused data-race by unblocking frame pump while coroutine was still reading reusableVisionBuffer
            } else {
                // F37-D6: a stuck vision coroutine used to drop every later
                // frame silently here, with no log at all. Throttled log so
                // the stall becomes observable instead of silent.
                visionBusySkips++
                val busyNow = System.currentTimeMillis()
                if (visionBusySkips == 1L || visionBusySkips % 100L == 0L || busyNow - lastVisionBusyLogMs > 300_000L) {
                    lastVisionBusyLogMs = busyNow
                    RuntimeLogger.log("VISION_BUSY_SKIP count=$visionBusySkips (vision coroutine still in flight; frame dropped)", "OVERLAY")
                }
                // No68-C-D18: the old 500 ms watchdog was removed for a
                // data race (it unblocked the frame pump while the
                // coroutine was still reading the shared buffer) and
                // nothing replaced it — so a hung vision coroutine dropped
                // every later frame FOREVER with only the throttled log
                // above. If the in-flight frame has been stuck > 5 s
                // (vision work is normally tens of ms), ask the main
                // thread to recreate the capture surfaces: that cancels
                // visionScope children, whose finally block resets
                // visionInFlight. One attempt per stall; the flag resets
                // when a frame completes. The 5 s line is deliberately
                // generous — this is a liveness net, not a performance
                // throttle, and it never touches visionInFlight itself.
                if (visionStartTimeMs > 0L && busyNow - visionStartTimeMs > 5_000L && !visionStuckHealArmed) {
                    visionStuckHealArmed = true
                    // B1: name the blocking stage — the last stage the
                    // wedged coroutine reached (visionStage) is the
                    // suspect; without it the heal log only counts skips.
                    RuntimeLogger.log("VISION_STUCK inFlightMs=${busyNow - visionStartTimeMs} busySkips=$visionBusySkips stage=$visionStage stageMs=${busyNow - visionStageStartedMs} (recreating capture surfaces)", "OVERLAY")
                    keepAliveHandler.post {
                        try {
                            // N4: cancelChildren() alone cannot stop the
                            // wedged coroutine (zero suspension points in
                            // its body), so abandon it first — generation
                            // bump + flag reset — then recreate. Guarded by
                            // the same 5 s staleness: if the old coroutine
                            // already returned and a fresh one started, its
                            // visionStartTimeMs is fresh and must NOT be
                            // abandoned. recreateCaptureSurfaces() still
                            // runs unconditionally (pre-existing behaviour).
                            if (visionStartTimeMs > 0L && System.currentTimeMillis() - visionStartTimeMs > 5_000L) {
                                abandonWedgedVision()
                            }
                            recreateCaptureSurfaces()
                        } catch (_: Throwable) {}
                    }
                }
            }

        // C1 (batch 5b — P7 REVERTED): the listener must never compare
        // against the live Image plane buffer. P7 moved the dedup compare
        // here, but a rotation closes the old reader (recreateCaptureSurfaces)
        // while the listener is mid-compare, so CaptureContent.same() threw
        // IllegalStateException "buffer is inaccessible" (v219 C1,
        // 2026-11-19 16:35:47). The dedup runs in the coroutine below, on
        // the app-owned copy, exactly as at 0661b53a. The OCR bitmap copy
        // below still runs for every frame, as before.

        val visionBuffer: java.nio.ByteBuffer = if (startVision) {
            val required = originalBuffer.remaining()
            val existing = reusableVisionBuffer
            // N4: allocate a FRESH buffer when the generation has advanced
            // since the last copy — the previous generation's buffer may
            // still be read by an abandoned coroutine returning late, and
            // reusing it here would overwrite the bytes it is reading
            // (the data race that killed the old 500 ms watchdog).
            val buffer: java.nio.ByteBuffer = if (existing == null || existing.capacity() < required || visionGeneration.get() != visionBufferGeneration) {
                val allocated = java.nio.ByteBuffer.allocateDirect(if (required > 0) required else 1)
                reusableVisionBuffer = allocated
                visionBufferGeneration = visionGeneration.get()
                allocated
            } else {
                existing
            }
            buffer.clear()
            buffer.limit(required)
            buffer.put(originalBuffer)
            originalBuffer.rewind()
            buffer.flip()
            buffer
        } else {
            emptyVisionBuffer
        }
        // SPLENDOR_V23B_VISION_BUFFER_REUSE_END
        
        // Deep copy for OCR (sync safe via reusableBitmap)
        val ocrReady = try {
            if (reusableBitmap == null || reusableBitmap!!.width != width || reusableBitmap!!.height != height) {
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            reusableBitmap!!.copyPixelsFromBuffer(originalBuffer)
            true
        } catch (_: Throwable) { false }

        // CLOSE IMAGE IMMEDIATELY: Frees native surface, prevents queue backup and faults
        image.close()

        // Launch Vision Coroutine with SAFE copied buffer
        if (startVision) visionScope.launch {
            val visionStartedNanos = System.nanoTime()
            // N4: the generation this coroutine belongs to. Abandoning a
            // wedged coroutine bumps visionGeneration; this coroutine must
            // then return early (guards below) and must not clear the new
            // frame's flag in finally.
            val frameGeneration = visionGeneration.get()
            // C1 (batch 5b): t0 keeps the visionStartedNanos initializer —
            // the restored in-coroutine dedup block READS t0 first
            // (stage("dedup", t0)), so the initializer is genuinely used,
            // not redundant. The old "initializer is redundant" warning
            // fired only because the P7-era coroutine reassigned t0 before
            // its first read; with the dedup read restored, the warning
            // cannot fire. The explicit `: Long` type stays.
            var t0: Long = visionStartedNanos
            visionStage = "launch"
            visionStageStartedMs = System.currentTimeMillis()
            // N4: per-stage heartbeat. The body has zero suspension
            // points, so if it wedges the last stage named here is the
            // blocker; stages over 100 ms are logged immediately.
            fun stage(name: String, startedNanos: Long) {
                visionStage = name
                visionStageStartedMs = System.currentTimeMillis()
                val dtMs = (System.nanoTime() - startedNanos) / 1_000_000L
                // B1: the stage profiler records every stage's duration
                // (VISION_STAGE summary lines + VISION_STAGE_STUCK
                // alerts) — the slow-stage log below alone could never
                // name the wedge's blocking stage.
                try { com.assistant.VisionStageProfiler.record(name, dtMs) } catch (_: Throwable) {}
                if (dtMs > 100L) {
                    try { RuntimeLogger.log("VISION_STAGE_SLOW stage=$name dtMs=$dtMs", "OVERLAY") } catch (_: Throwable) {}
                }
            }
            try {
                com.assistant.CrashForensics.mark(3, 2, captureFrameCount)
                // Exact byte comparison: repeated capture content must not trigger another action.
                if (previousVisionLayout == Triple(width, height, rowStride) && previousVisionBuffer?.let { prior ->
                    com.assistant.runtime.CaptureContent.same(visionBuffer, prior, width, height, rowStride, pixelStride) { x, y ->
                        com.assistant.vision.OverlaySelfMask.isSelfDrawnCapture(x, y)
                    }
                } == true) {
                    // SPLENDOR_P6_DEDUP_DIAG_BEGIN
                    // A duplicate frame skips VisionCore/FrameAssembler/onFrame,
                    // so the decision counter does not advance even though
                    // capture is alive: prime suspect for LOOP_FROZEN. Log
                    // throttled (first + every 300th + at most every 5 min).
                    duplicateFrameCount++
                    val dupNow = System.currentTimeMillis()
                    if (duplicateFrameCount == 1L || duplicateFrameCount % 300L == 0L || dupNow - lastDuplicateLogMs > 300_000L) {
                        lastDuplicateLogMs = dupNow
                        RuntimeLogger.log("DEDUP_SKIP count=$duplicateFrameCount layout=" + width + "x" + height + " stride=$rowStride (identical frame: onFrame skipped, decision counter not advanced)", "OVERLAY")
                    }
                    // SPLENDOR_P6_DEDUP_DIAG_END
                    com.assistant.FrameAssembler.current()?.let { com.assistant.ActionOutcomeVerifier.verify(it) }
                    return@launch
                }
                stage("dedup", t0); t0 = System.nanoTime()
                // N4: late return from dedup after an abandon — do not
                // write shared state or dispatch on a stale frame.
                if (frameGeneration != visionGeneration.get()) return@launch
                previousVisionLayout = Triple(width, height, rowStride)
                val normalized = com.assistant.FrameNormalizer.normalize(visionBuffer, width, height, rowStride, pixelStride, capturedAtNanos)
                stage("normalize", t0); t0 = System.nanoTime()
                com.assistant.CrashForensics.mark(4, 2, captureFrameCount)
                val prevBuf = previousVisionBuffer
                val opticalVariance = if (prevBuf != null && prevBuf.remaining() == visionBuffer.remaining()) {
                    try { com.assistant.NativeBridge.nativeComputeOpticalVariance(visionBuffer, prevBuf, width, height, rowStride, 0, 0, width, height).coerceIn(0f, 1f) } catch (_: Throwable) { 0f }
                } else 0f
                stage("opticalVariance", t0); t0 = System.nanoTime()
                val cap = visionBuffer.remaining()
                val prevStore = previousVisionBuffer
                val targetBuf = if (prevStore == null || prevStore.capacity() < cap) {
                    val alloc = java.nio.ByteBuffer.allocateDirect(cap)
                    previousVisionBuffer = alloc
                    alloc
                } else {
                    prevStore
                }
                targetBuf.clear()
                val pos = visionBuffer.position()
                targetBuf.put(visionBuffer)
                visionBuffer.position(pos)
                targetBuf.flip()
                com.assistant.ControlMappingTrainer.observe(visionBuffer, width, height, rowStride, pixelStride)
                stage("observe", t0); t0 = System.nanoTime()
                val state = com.assistant.VisionCore.process(normalized)
                stage("visionCore", t0); t0 = System.nanoTime()
                // N4: late return from VisionCore after an abandon — do not
                // train, ignite, assemble, or dispatch on a stale frame.
                // (The shared previousVisionBuffer copy above may already
                // have run with stale bytes; that only perturbs one
                // frame's variance/dedup input, it cannot dispatch.)
                if (frameGeneration != visionGeneration.get()) return@launch
                com.assistant.CrashForensics.mark(4, 2, captureFrameCount)
                com.assistant.BoosterIgnition.ensureIgnited(this@OverlayService)
                com.assistant.AppContributorRegistration.ensureRegistered()
                com.assistant.RuntimeCoordinator.reportCaptureReady()
                com.assistant.CrashForensics.mark(5, 3, captureFrameCount)
                com.assistant.CrashForensics.setEngineStage(5)
                val frame = com.assistant.FrameAssembler.assemble(opticalVariance, capturedAtNanos)
                stage("assemble", t0); t0 = System.nanoTime()
                // F37-D7: cross-process capture heartbeat for LagVerdictEngine
                // (:survival cannot see FrameAssembler — a main-process object).
                try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishCaptureHeartbeat() } catch (_: Throwable) {}
                com.assistant.CrashForensics.mark(6, 3, captureFrameCount)
                com.assistant.RuntimeDecisionLoop.onFrame(frame)
                stage("onFrame", t0); t0 = System.nanoTime()
                com.assistant.CrashForensics.mark(6, 3, captureFrameCount)
                // WIRE SILOED GK ENGINES: Activate Dive, Claim, Rush, and Block action engines
                com.assistant.GameStateBuilder.update(state)
                com.assistant.overlay.interceptor.OmnipotentGoalkeeperEngine.scanFrameForOpponentAnimation(visionBuffer, width, height, rowStride)
                stage("gameState+keeperScan", t0)
            } catch (t: Throwable) {
                val now = System.currentTimeMillis()
                if (now - lastCaptureFaultLog > 1000L) {
                    lastCaptureFaultLog = now
                    try { RuntimeLogger.log("CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message, "FAULT") } catch (_: Throwable) {}
                }
            } finally {
                com.assistant.PerformanceHintEngine.reportActualWorkload(
                    this@OverlayService, System.nanoTime() - visionStartedNanos,
                    com.assistant.MemoryCaptureGateEngine.targetFps
                )
                // N4: only the CURRENT generation may clear the flag and
                // re-arm the heal. An abandoned coroutine returning late
                // used to clear a fresh frame's flag here, dropping every
                // later frame until the next restart.
                if (frameGeneration == visionGeneration.get()) {
                    visionInFlight.set(false)
                    // No68-C-D18: a completed frame clears the stuck-heal arm
                    // so a later, separate stall can heal again.
                    visionStuckHealArmed = false
                }
            }
        }

        // Trigger OCR if interval met (uses already-populated reusableBitmap)
        val shedFactor = when (com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.currentLoadShed()) {
            "HEAVY" -> 4L
            "LIGHT" -> 2L
            else -> 1L
        }

        // No68-C-D19: OCR used to run inline at the end of the capture
        // listener, on the same thread that must acquire the next frame —
        // the bitmap snapshot copy blocked the next acquireLatestImage.
        // Post it to the OCR handler instead so the listener returns and
        // the next frame can be acquired; processBitmapForOCR then reads
        // the latest bitmap, which is the right frame for advisory OCR at
        // a 1.5 s cadence.
        if (ocrReady && System.currentTimeMillis() - lastOcrTime >= OCR_INTERVAL_MS * shedFactor) {
            lastOcrTime = System.currentTimeMillis()
            val ocrHandler = ocrIoHandler ?: ocrIoThread?.looper?.let { Handler(it) }
            if (ocrHandler != null) ocrHandler.post { processBitmapForOCR() } else processBitmapForOCR()
        }
    }

    fun applyFreshProjection(code: Int, data: Intent) {
        captureLock.lock()
        try {
            setupMediaProjectionInternal(code, data)
            RuntimeLogger.log("AGENT CAPTURE RESTORED: Fresh MediaProjection token applied successfully", "AGENT")
        } finally {
            captureLock.unlock()
        }
    }

    // SPLENDOR_V42_AUTOHEAL_METHOD_BEGIN
    // Automatic capture recovery — no manual tap required.
    //  - Capture alive but stale: restart the ImageReader (no token, no tap).
    //  - Revoked/failed/idle: token reuse (zero interaction) when the session
    //    is still alive; otherwise auto-launch the system consent dialog
    //    (one tap — Android 14 requires one consent per capture session; no
    //    app can bypass that, but the app handles everything else itself).
    fun autoHealCapture(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAutoHealAttemptMs < 15_000L) return false  // anti-spam cooldown
        lastAutoHealAttemptMs = now
        val st = readCaptureState()
        if (st == CaptureState.ACTIVE || st == CaptureState.AUTHORIZED) {
            val ok = try { restartCapture() } catch (_: Throwable) { false }
            RuntimeLogger.log("AUTO_HEAL: capture alive but stale -> restartCapture() = " + ok, "OVERLAY")
            return ok
        }
        val code = savedProjectionCode
        val data = savedProjectionData
        if (code == 0 || data == null) {
            RuntimeLogger.log("AUTO_HEAL: no saved token; requesting fresh authorization", "OVERLAY")
            launchReauthActivity()
            return false
        }
        // Attempt 1: token reuse — zero user interaction.
        try {
            startForegroundSafely()
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = pm.getMediaProjection(code, data)
            if (mp != null) {
                captureLock.lock()
                try {
                    setupMediaProjectionInternal(code, data)
                    RuntimeLogger.log("AUTO_HEAL: token reuse succeeded — capture restored without user interaction", "OVERLAY")
                    return true
                } finally {
                    captureLock.unlock()
                }
            }
        } catch (t: Throwable) {
            RuntimeLogger.log("AUTO_HEAL: token reuse failed (" + t.javaClass.simpleName + ": " + t.message + ")", "OVERLAY")
        }
        // Attempt 2: auto re-prompt — one system consent tap (Android 14 rule).
        RuntimeLogger.log("AUTO_HEAL: token dead; auto-launching system consent (single tap)", "OVERLAY")
        launchReauthActivity()
        return false
    }

    private fun launchReauthActivity() {
        try {
            val i = Intent(this, SplendorReauthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (t: Throwable) {
            RuntimeLogger.log("AUTO_HEAL: reauth activity launch failed (" + t.javaClass.simpleName + ": " + t.message + ")", "OVERLAY")
        }
    }
    // SPLENDOR_V42_AUTOHEAL_METHOD_END

    override fun onBind(intent: Intent?): IBinder? = null

    // N4: abandon a wedged vision coroutine. cancelChildren() cannot stop
    // it — the body has zero suspension points, so cancellation is never
    // observed — and its finally is the ONLY writer that clears
    // visionInFlight. Abandoning means: cancel (harmless if it ever does
    // suspend), bump the generation so the late finally cannot clear a
    // fresh frame's flag and the late guards return early, clear the flag
    // so the next frame can start, and re-arm the stuck-heal so a later
    // stall heals again. Safe to call when nothing is wedged: the CAS
    // fails and nothing changes. The abandoned coroutine keeps its
    // Default worker until it returns (leak: one worker per wedge) and
    // keeps its own (now per-generation) buffer — see the listener copy.
    // Callers: the VISION_STUCK heal and restartCapture().
    private fun abandonWedgedVision() {
        if (!visionInFlight.compareAndSet(true, false)) return
        val generation = visionGeneration.incrementAndGet()
        visionStuckHealArmed = false
        val stuckMs = if (visionStartTimeMs > 0L) System.currentTimeMillis() - visionStartTimeMs else -1L
        try { RuntimeLogger.log("VISION_ABANDONED generation=$generation stage=$visionStage stageMs=${if (visionStageStartedMs > 0L) System.currentTimeMillis() - visionStageStartedMs else -1L} inFlightMs=$stuckMs busySkips=$visionBusySkips (wedged coroutine abandoned; next frame may start)", "OVERLAY") } catch (_: Throwable) {}
    }

    fun restartCapture(): Boolean {
        captureLock.lock()
        try {
            if (captureState == CaptureState.REVOKED) {
                RuntimeLogger.log("AGENT CAPTURE RESTART: projection already revoked; fresh MediaProjection authorization required", "AGENT")
                return false
            }
            if (mediaProjection == null) {
                RuntimeLogger.log("AGENT CAPTURE RESTART: no active MediaProjection", "AGENT")
                return false
            }
            try {
                RuntimeLogger.log("AGENT CAPTURE RESTART: attempting ImageReader replacement", "AGENT")
                val result = recreateCaptureSurfacesInternal()
                if (result != CaptureResult.SUCCESS) {
                    RuntimeLogger.log("AGENT CAPTURE RESTART FAILED: $result", "AGENT")
                    return false
                }
                lastFrameProcessedMs = 0L
                captureFrameCount = 0L
                // N4: a restart must reset the vision pipeline, not just
                // the ImageReader — abandon any wedged coroutine so the
                // next frame can start (previously the flag stayed set and
                // every later frame was VISION_BUSY_SKIP).
                abandonWedgedVision()
                RuntimeLogger.log("AGENT CAPTURE RESTART: ImageReader replaced successfully", "AGENT")
                return true
            } catch (e: Exception) {
                RuntimeLogger.log("AGENT CAPTURE RESTART FAILED: ${e.message}", "AGENT")
                return false
            }
        } finally {
            captureLock.unlock()
        }
    }

    override fun onCreate() {
        com.assistant.CrashForensics.install()
        com.assistant.SplendorCaptureRecovery.attach(this)
        // SplendorWatchdog removed: WatchdogAdapterService handles survival process watchdog
        if(runtimeInitialized) return
        runtimeInitialized = true

        super.onCreate()

        // V53: Prevent HyperOS CPU suspension and Network interface sleep
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SplendorAssist:VisionPipelineLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours max safety limit
            
            val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SplendorAssist:NetPipelineLock")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
            
            com.assistant.diagnostic.RuntimeLogger.log("V53 WAKE_LOCK & WIFI_LOCK acquired: Preventing HyperOS pipeline suspension.", "PERFORMANCE")
        } catch (t: Throwable) { com.assistant.diagnostic.RuntimeLogger.log("WAKE/WIFI LOCK acquire failed: ${t.message}", "OVERLAY") }

        RuntimeLogger.log("OverlayService started", "OVERLAY")
        com.assistant.vision.ForegroundGate.install(application)
        // SPLENDOR_CONTROL_MAP_V1 - RadarScaleCalibrator was never calibrated,
        // so its logical-pitch -> screen mapping was permanently at its default
        // 1080x2340 with unit scales. Calibrate from the real display once the
        // service is up so the mapping is usable and not dead infrastructure.
        try { com.assistant.RadarScaleCalibrator.calibrate(applicationContext) } catch (t: Throwable) { com.assistant.diagnostic.RuntimeLogger.log("RadarScaleCalibrator.calibrate failed: ${t.message}", "OVERLAY") }

        try { com.assistant.RuntimeSelfHealEngine.init(applicationContext)
              com.assistant.RuntimeSelfHealEngine.start() } catch (t: Throwable) { com.assistant.diagnostic.RuntimeLogger.log("RuntimeSelfHealEngine init/start failed: ${t.message}", "OVERLAY") }
        // F37-D5: register contributors at service start, not only after the
        // first assembled frame — otherwise a never-started capture leaves
        // the registry at 0/52 forever. The frame-path call stays as well.
        try { com.assistant.AppContributorRegistration.ensureRegistered() } catch (t: Throwable) { com.assistant.diagnostic.RuntimeLogger.log("AppContributorRegistration.ensureRegistered at startup failed: ${t.message}", "OVERLAY") }
        try { com.assistant.CaptaincySkillEngine.init(applicationContext) } catch (t: Throwable) { com.assistant.diagnostic.RuntimeLogger.log("CaptaincySkillEngine.init failed: ${t.message}", "OVERLAY") }
        try { com.assistant.CrowdingZoneDetector.init(applicationContext) } catch (t: Throwable) { com.assistant.diagnostic.RuntimeLogger.log("CrowdingZoneDetector.init failed: ${t.message}", "OVERLAY") }

        instance = this
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        ChoreographerRenderLoop.start()
        ocrIoThread = android.os.HandlerThread("OverlayOCRThread", android.os.Process.THREAD_PRIORITY_DEFAULT).apply { start() }
        ocrIoHandler = android.os.Handler(ocrIoThread!!.looper)
        initializeOverlayUI()
        interceptionBridge = com.assistant.overlay.bridge.InterceptionControlRoomBridge(com.assistant.overlay.repository.InterceptionRepository.shared(this)).also { it.start() }
        modMenuController = ModMenuController(this).also { it.show() }
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()

        val resultCode = intent?.getIntExtra("CROSS_PROCESS_CODE", EngineData.code) ?: EngineData.code
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("CROSS_PROCESS_DATA", Intent::class.java) ?: EngineData.intent
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("CROSS_PROCESS_DATA") ?: EngineData.intent
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                setupMediaProjection(resultCode, data)
                if (!isRunning) {
                    initializeProcessingEngine()
                }
            } catch (e: Exception) {
                logSilentFailure(e)
                // SPLENDOR_P6_NO_STOPSELF_BEGIN
                // Do NOT stopSelf(): a dead service + a still-running agent
                // is the session-2 zombie. Try auto-heal, else show the
                // recovery prompt and stay alive for a fresh token.
                val healedAfterFault = try { autoHealCapture() } catch (_: Throwable) { false }
                if (!healedAfterFault) {
                    try { showCaptureRecoveryPrompt() } catch (_: Throwable) {}
                }
                // SPLENDOR_P6_NO_STOPSELF_END
            }
        } else {
            // SPLENDOR_P6_STICKY_RESTART_BEGIN
            // A sticky restart after process death arrives with a null intent
            // and EngineData empty (code=0): the old code logged and called
            // stopSelf(), destroying the service while the self-heal agent
            // kept running -> session-2 zombie (0 frames, 5,205x COLLECT_ZERO
            // over 2h25m). Stay alive instead: try token reuse first (works
            // when the service object survived), otherwise show the capture
            // recovery prompt so a fresh consent can be delivered via
            // applyFreshProjection on this live instance.
            RuntimeLogger.log("onStartCommand: no valid projection token (resultCode=$resultCode); attempting auto-heal, else recovery prompt", "OVERLAY")
            val healed = try { autoHealCapture() } catch (_: Throwable) { false }
            if (healed) {
                if (!isRunning) {
                    initializeProcessingEngine()
                }
            } else {
                try { showCaptureRecoveryPrompt() } catch (_: Throwable) {}
            }
            // SPLENDOR_P6_STICKY_RESTART_END
        }
        return START_STICKY
    }

    private fun logSilentFailure(e: Exception) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val logFile = com.assistant.storage.SplendorStorageRoot.file("Splendor_Crash_Reports.txt")
            FileWriter(logFile, true).use { writer ->
                PrintWriter(writer).use { pw ->
                    pw.println("=== SILENT ENGINE FAULT: $timestamp ===")
                    e.printStackTrace(pw)
                    pw.println("=========================================\n")
                }
            }
        } catch (t: Throwable) { android.util.Log.e("OverlayService", "silent-fault log failed, fault record lost", t) }
    }

    private fun startForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Engine Primary", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Splendor Assist Locked")
            .setContentText(com.assistant.SplendorCaptureRecovery.statusText("Engine Active"))
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .build()

        // C2/C3 (batch 5b): the MediaProjection grant dies with the crashed
        // process, so a sticky restart after ANY crash threw SecurityException
        // here (starting FGS with type mediaProjection requires the grant)
        // and the service died ~5 s after boot before the token / auto-heal /
        // recovery-prompt logic could run (v219, 2026-11-19 16:35:55 +
        // 16:51:29). Try mediaProjection first; on SecurityException fall
        // back to SPECIAL_USE (declared in the manifest with a
        // PROPERTY_SPECIAL_USE_FGS_SUBTYPE); if that also throws, log and
        // continue — NEVER rethrow — so the recovery logic below runs.
        // Pre-UPSIDE_DOWN_CAKE branch (bare startForeground): unchanged on
        // purpose — the grant-death enforcement is a U+ mediaProjection
        // rule, and the target device is API 35, so the >=34 branch is
        // the live one; <34 behaviour is not silently changed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } catch (e: SecurityException) {
                try {
                    android.util.Log.w("OverlayService", "mediaProjection FGS refused (grant died with previous process); falling back to specialUse", e)
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } catch (e2: Throwable) {
                    android.util.Log.e("OverlayService", "specialUse FGS fallback also failed; continuing without foreground promotion so recovery can run", e2)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("InflateParams")
    private fun initializeOverlayUI() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // No65-D1 (THE CRASH): the inflater used to come from the raw
        // service context, whose theme is the system
        // Theme.DeviceDefault.Light.DarkActionBar — it cannot resolve the
        // app's ?attr/colorTertiary, so TextView construction threw
        // UnsupportedOperationException and onCreate died (6 crashes in
        // ~21 s, 23:53:19–23:53:40). Inflate with a ContextThemeWrapper
        // carrying Theme.SplendorAssist instead; the service context
        // never gets <application android:theme> (Activities only).
        val themedContext = ContextThemeWrapper(this, com.assistant.overlay.R.style.Theme_SplendorAssist)
        val inflater = LayoutInflater.from(themedContext)
        overlayView = inflater.inflate(com.assistant.overlay.R.layout.overlay_layout, null)
        txtEngineStatus = overlayView.findViewById(com.assistant.overlay.R.id.overlay_status_text)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlayView, layoutParams)
        
        // REMOVED: Panic indicator painting to guarantee zero app-owned pixels in MediaProjection capture surface
        
        overlayView.post {
            com.assistant.vision.OverlaySelfMask.publishHierarchy("hud", overlayView)
        }
        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            com.assistant.vision.OverlaySelfMask.publishHierarchy("hud", overlayView)
        }
        overlayView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        OverlaySurvivalEngine.attached()
        // SPLENDOR_F34_TRUTH: truthful status instead of decorative claim.
        updateOverlayVisuals("🕶️ SPLENDOR ASSIST ACTIVE", Color.GREEN)
        startTrajectoryWatchdog()
    }

    private fun updateOverlayVisuals(text: String, color: Int) {
        // No68-C-D20: txtEngineStatus is GONE from the first capture start
        // (capture purity: zero app-owned pixels in the MediaProjection
        // surface), so every later update posted Choreographer work that
        // painted nothing. Skip it; the pre-capture call still paints.
        if (!::txtEngineStatus.isInitialized) return
        if (txtEngineStatus.visibility != View.VISIBLE) return
        ChoreographerRenderLoop.postUpdate {
            txtEngineStatus.text = if (CallOverlayRepository.incomingCallVisible) "[CALL PROTECTED] " + text else text
            txtEngineStatus.setTextColor(color)
        }
    }

    fun showCaptureRecoveryPrompt() {
        Handler(Looper.getMainLooper()).post {
            try {
                if (recoveryPromptShown) return@post
                recoveryPromptShown = true
                val prompt = TextView(this).apply {
                    text = "⚠️ CAPTURE STOPPED - TAP TO RESTORE"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 14f
                    setPadding(24, 18, 24, 18)
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        dismissCaptureRecoveryPrompt()
                        requestFreshProjectionAuthorization()
                    }
                }
                recoveryPromptView = prompt
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // SPLENDOR_FG_TRUTH_V1 - FLAG_NOT_FOCUSABLE keeps the
                    // recovery prompt from taking input focus away from the
                    // running game while it is on screen (it stays tappable).
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = 120
                windowManager.addView(prompt, params)
                postRecoveryNotification()
                RuntimeLogger.log("CAPTURE RECOVERY PROMPT shown (user tap restores authorization)", "AGENT")
            } catch (t: Throwable) {
                recoveryPromptShown = false
                RuntimeLogger.log("CAPTURE RECOVERY PROMPT failed: ${t.javaClass.simpleName}: ${t.message}", "AGENT")
            }
        }
    }

    private fun dismissCaptureRecoveryPrompt() {
        recoveryPromptView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        recoveryPromptView = null
        recoveryPromptShown = false
        try { notificationManager.cancel(1103) } catch (_: Throwable) {}
    }

    private fun requestFreshProjectionAuthorization() {
        try {
            val intent = Intent("com.assistant.REQUEST_PROJECTION").setPackage(packageName)
            sendBroadcast(intent)
            RuntimeLogger.log("Requested fresh MediaProjection authorization (broadcast com.assistant.REQUEST_PROJECTION)", "AGENT")
        } catch (t: Throwable) {
            RuntimeLogger.log("Failed to request fresh projection: ${t.message}", "AGENT")
        }
    }

    private fun postRecoveryNotification() {
        try {
            val channel = NotificationChannel("capture_recovery", "Capture Recovery", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
            val notification = NotificationCompat.Builder(this, "capture_recovery")
                .setContentTitle("Capture Stopped")
                .setContentText("Tap to restore AI agent capture")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .build()
            notificationManager.notify(1103, notification)
        } catch (_: Throwable) {}
    }

    private fun setupMediaProjection(code: Int, intent: Intent) {
        captureLock.lock()
        try {
            setupMediaProjectionInternal(code, intent)
        } finally {
            captureLock.unlock()
        }
        startCaptureKeepAlive()
    }

    private fun setupMediaProjectionInternal(code: Int, intent: Intent) {
        com.assistant.PerformanceHintEngine.beginCapture()
        // Clear transient panic state on fresh capture start to prevent stale contamination
        SmartAssistRepository.clearPanic()
        
        // Guarantee zero app-painted pixels enter MediaProjection capture surface
        try { txtEngineStatus.visibility = View.GONE } catch (_: Throwable) {}
        try { panicIndicator?.visibility = View.GONE } catch (_: Throwable) {}
        
        if (captureState == CaptureState.ACTIVE || captureState == CaptureState.AUTHORIZED) {
            teardownCaptureResourcesInternal()
        }

        startForegroundSafely()
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = projectionManager.getMediaProjection(code, intent)

        if (mp == null) {
            captureState = CaptureState.FAILED
            // f5: failed capture must not leave the mod menu up.
            modMenuController?.hideLauncher()
            modMenuController?.hide()
            RuntimeLogger.log("MediaProjection setup failed: getMediaProjection returned null", "OVERLAY")
            throw IllegalStateException("MediaProjection unavailable")
        }

        mediaProjection = mp

        // SPLENDOR_V42_AUTOHEAL_TOKENSAVE_BEGIN
        savedProjectionCode = code
        savedProjectionData = intent
        // SPLENDOR_V42_AUTOHEAL_TOKENSAVE_END

        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                val stoppedMp = mediaProjection
                Handler(Looper.getMainLooper()).post {
                    // SPLENDOR_V42_AUTOHEAL_ONSTOP_BEGIN
                    // Ignore transient stops during re-setup (a newer projection
                    // already replaced this one) — prevents a heal loop.
                    if (stoppedMp != null && stoppedMp !== mediaProjection) return@post
                    // SPLENDOR_V42_AUTOHEAL_ONSTOP_END
                    teardownCaptureResources(CaptureState.REVOKED)
                    RuntimeLogger.log("MediaProjection.onStop(): projection revoked; auto-healing capture.", "OVERLAY")
                    // SPLENDOR_V42_AUTOHEAL_ONSTOP_BEGIN
                    // Auto-recover instead of waiting for a manual tap. Small
                    // delay lets the system settle after the revoke.
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { autoHealCapture() } catch (_: Throwable) {}
                    }, 2000L)
                    // SPLENDOR_V42_AUTOHEAL_ONSTOP_END
                }
            }
        }
        projectionCallback = cb
        mediaProjection?.registerCallback(cb, Handler(Looper.getMainLooper()))

        captureState = CaptureState.AUTHORIZED
        recreateCaptureSurfacesInternal()
    }

    // No68-C-D16: the wrapper used to discard recreateCaptureSurfacesInternal's
    // CaptureResult, so the keepalive could not tell success from failure.
    private fun recreateCaptureSurfaces(): CaptureResult {
        captureLock.lock()
        try {
            return recreateCaptureSurfacesInternal()
        } finally {
            captureLock.unlock()
        }
    }

    private fun recreateCaptureSurfacesInternal(): CaptureResult {
        if (mediaProjection == null) {
            RuntimeLogger.log("recreateCaptureSurfaces: mediaProjection is null", "OVERLAY")
            return CaptureResult.INVALID_SESSION
        }
        if (captureState != CaptureState.AUTHORIZED && captureState != CaptureState.ACTIVE) {
            RuntimeLogger.log("recreateCaptureSurfaces: invalid state $captureState", "OVERLAY")
            return CaptureResult.INVALID_SESSION
        }

        val scale = 0.4f
        val metrics = DisplayMetrics()
        var finalWidth: Int
        var finalHeight: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            finalWidth = (bounds.width() * scale).toInt() and 0xFFFFFFFE.toInt()
            finalHeight = (bounds.height() * scale).toInt() and 0xFFFFFFFE.toInt()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            finalWidth = (metrics.widthPixels * scale).toInt() and 0xFFFFFFFE.toInt()
            finalHeight = (metrics.heightPixels * scale).toInt() and 0xFFFFFFFE.toInt()
        }

        // SPLENDOR_V42_ZERO_CAPTURE_GUARD_BEGIN
        // Field log 2026-10-02 15:47: capture=0x0 source=0x0 -> ImageReader
        // created with 0x0 dimensions -> no frame ever delivered -> COLLECT_ZERO
        // spam + EARLY DEATH. windowManager.currentWindowMetrics.bounds can
        // return 0x0 when the service starts before the display is ready.
        if (finalWidth <= 0 || finalHeight <= 0) {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            finalWidth = (metrics.widthPixels * scale).toInt() and 0xFFFFFFFE.toInt()
            finalHeight = (metrics.heightPixels * scale).toInt() and 0xFFFFFFFE.toInt()
            RuntimeLogger.log("ZERO_CAPTURE_GUARD: window metrics 0x0, fell back to display " + finalWidth + "x" + finalHeight, "OVERLAY")
        }
        if (finalWidth <= 0 || finalHeight <= 0) {
            // Last resort: fixed 288x640 (0.4x of 720x1600) so capture can never be 0x0.
            finalWidth = 288
            finalHeight = 640
            RuntimeLogger.log("ZERO_CAPTURE_GUARD: display metrics also 0x0, forced 288x640", "OVERLAY")
        }
        // SPLENDOR_V42_ZERO_CAPTURE_GUARD_END

        com.assistant.vision.OverlaySelfMask.setCaptureScale(finalWidth, finalHeight, if (scale > 0f) (finalWidth / scale).toInt() else finalWidth, if (scale > 0f) (finalHeight / scale).toInt() else finalHeight)
        // SPLENDOR_V12A_CAMERA_PROFILE_BEGIN
        com.assistant.vision.CameraProfile.setCaptureScale(
            finalWidth,
            finalHeight,
            if (scale > 0f) (finalWidth / scale).toInt() else finalWidth,
            if (scale > 0f) (finalHeight / scale).toInt() else finalHeight
        )
        com.assistant.vision.CameraProfile.logDiagnosticsOnce()
        // SPLENDOR_V12A_CAMERA_PROFILE_END
        // N5: publish the live capture bounds to core so the dispatch
        // choke point (HybridExecutionTerminal.route) can clamp requests
        // to this frame — core cannot read CameraProfile (app module).
        try { com.assistant.execution.DispatchBounds.set(finalWidth.toFloat(), finalHeight.toFloat()) } catch (_: Throwable) {}

        val isInitial = virtualDisplay == null
        val dimensionsChanged = finalWidth != currentWidth || finalHeight != currentHeight || metrics.densityDpi != currentDpi

        // SPLENDOR_P6_GEOMETRY_LOG_BEGIN
        // Log live geometry + orientation on every (re)create so the
        // 288x640-vs-640x288 question is answerable from the log alone.
        if (isInitial || dimensionsChanged) {
            RuntimeLogger.log("CAPTURE_GEOMETRY " + finalWidth + "x" + finalHeight + " orientation=" + (if (finalWidth < finalHeight) "PORTRAIT" else "LANDSCAPE") + " source=" + (if (scale > 0f) (finalWidth / scale).toInt() else finalWidth) + "x" + (if (scale > 0f) (finalHeight / scale).toInt() else finalHeight), "OVERLAY")
        }
        // SPLENDOR_P6_GEOMETRY_LOG_END

        if (isInitial) {
            val reader = ImageReader.newInstance(finalWidth, finalHeight, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener(imageAvailableListener, ocrIoHandler ?: ocrIoThread?.looper?.let { Handler(it) } ?: Handler(Looper.getMainLooper()))
            imageReader = reader
            virtualDisplay = mediaProjection?.createVirtualDisplay("HybridCoachScreen", finalWidth, finalHeight, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, reader.surface, null, null)
        } else {
            val oldReader = imageReader
            val newReader = ImageReader.newInstance(finalWidth, finalHeight, PixelFormat.RGBA_8888, 2)
            newReader.setOnImageAvailableListener(imageAvailableListener, ocrIoHandler ?: Handler(Looper.getMainLooper()))
            
            imageReader = newReader
            
            if (dimensionsChanged) {
                virtualDisplay?.resize(finalWidth, finalHeight, metrics.densityDpi)
            }
            virtualDisplay?.setSurface(newReader.surface)
            
            try { oldReader?.close() } catch (_: Throwable) {}
            
            // V38 LOOP_FROZEN FIX: Force-kill stuck vision coroutines safely without race condition
            visionScope.coroutineContext.cancelChildren()
            // N6: a rotation changes the frame space (288x640 <-> 640x288).
            // The ball-velocity bridge keeps its previous position across
            // publishes with no space tag, so one velocity sample straddles
            // the rotation. Reset it when the dimensions change so the next
            // publish has no previous frame instead of a cross-era delta.
            if (dimensionsChanged) {
                try { com.assistant.BallTelemetryBridge.reset() } catch (_: Throwable) {}
            }
            // Do NOT prematurely force visionInFlight = false while background coroutine is still reading/assembling
            // visionInFlight resets cleanly in the coroutine finally block
            
            RuntimeLogger.log("recreateCaptureSurfaces: ImageReader replaced via setSurface (resize=$dimensionsChanged). Vision pipeline force-reset.", "OVERLAY")
        }
        
        currentWidth = finalWidth
        currentHeight = finalHeight
        currentDpi = metrics.densityDpi

        captureState = CaptureState.ACTIVE
        
        val reader = imageReader
        val vd = virtualDisplay
        
        return if (reader != null && vd != null && vd.surface == reader.surface && captureState == CaptureState.ACTIVE) {
            CaptureResult.SUCCESS
        } else {
            CaptureResult.FAILED
        }
    }

    private fun startCaptureKeepAlive() {
        keepAliveRunnable?.let { keepAliveHandler.removeCallbacks(it) }
        keepAliveRunnable = object : Runnable {
            override fun run() {
                val mpActive = try {
                    captureLock.lock()
                    mediaProjection != null && captureState != CaptureState.REVOKED
                } finally {
                    captureLock.unlock()
                }
                
                if (mpActive) {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameProcessedMs > 10000L && lastFrameProcessedMs > 0L) {
                        RuntimeLogger.log("KEEPALIVE: Capture stale for >10s. Proactively replacing ImageReader to prevent HyperOS silent kill.", "OVERLAY")
                        try {
                            // No68-C-D16: the old code reset the freshness
                            // timestamp unconditionally, so a FAILED recreate
                            // masked the stall for another 10 s and the
                            // keepalive could loop forever on a dead capture
                            // believing it had healed. Only a SUCCESS counts
                            // as fresh; a failure leaves the stale timestamp
                            // so the next 45 s poll (and the 30 s recovery
                            // checker) still see the real stall.
                            val result = recreateCaptureSurfaces()
                            if (result == CaptureResult.SUCCESS) {
                                lastFrameProcessedMs = System.currentTimeMillis()
                            } else {
                                RuntimeLogger.log("KEEPALIVE replace failed: $result (stale timestamp kept)", "OVERLAY")
                            }
                        } catch (e: Exception) {
                            RuntimeLogger.log("KEEPALIVE replace failed: ${e.message}", "OVERLAY")
                        }
                    }
                    // SPLENDOR_P6_ORIENTATION_BEGIN
                    // Capture geometry is sampled once at (re)create time and
                    // never re-checked on rotation: a service started in
                    // portrait keeps a 288x640 virtual display after the game
                    // rotates to landscape (live 288x640 vs expected 640x288).
                    // Recreate when the window bounds no longer match the live
                    // capture size, so geometry follows the display. Same 0.4
                    // scale as recreateCaptureSurfacesInternal.
                    try {
                        val orientW: Int
                        val orientH: Int
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val orientBounds = windowManager.currentWindowMetrics.bounds
                            orientW = (orientBounds.width() * 0.4f).toInt() and 0xFFFFFFFE.toInt()
                            orientH = (orientBounds.height() * 0.4f).toInt() and 0xFFFFFFFE.toInt()
                        } else {
                            val orientMetrics = DisplayMetrics()
                            @Suppress("DEPRECATION")
                            windowManager.defaultDisplay.getRealMetrics(orientMetrics)
                            orientW = (orientMetrics.widthPixels * 0.4f).toInt() and 0xFFFFFFFE.toInt()
                            orientH = (orientMetrics.heightPixels * 0.4f).toInt() and 0xFFFFFFFE.toInt()
                        }
                        if (orientW > 0 && orientH > 0 && (orientW != currentWidth || orientH != currentHeight)) {
                            RuntimeLogger.log("KEEPALIVE: display size changed " + currentWidth + "x" + currentHeight + " -> " + orientW + "x" + orientH + "; recreating capture surfaces (orientation change)", "OVERLAY")
                            recreateCaptureSurfaces()
                        }
                    } catch (e: Exception) {
                        RuntimeLogger.log("KEEPALIVE orientation check failed: " + e.message, "OVERLAY")
                    }
                    // SPLENDOR_P6_ORIENTATION_END
                }
                keepAliveHandler.postDelayed(this, 45000L)
            }
        }
        keepAliveHandler.postDelayed(keepAliveRunnable!!, 45000L)
    }

    private fun processBitmapForOCR() {
        if (reusableBitmap == null || reusableBitmap!!.isRecycled) return
        if (taskExecutionLock.tryLock()) {
            try {
                // P1: reuse a pooled snapshot bitmap instead of
                // allocating + recycling one per 1.5 s tick. The pool
                // fill is an exact byte copy (same pixels the fresh
                // copy held); on any pool failure fall back to the
                // legacy copy + recycle so OCR never loses a tick.
                val source = reusableBitmap!!
                var pooledSnapshot: Bitmap? = null
                var pooled = false
                try {
                    val acquired = com.assistant.SnapshotBufferPool.acquire(source.width, source.height, source.config)
                    if (com.assistant.SnapshotBufferPool.fill(acquired, source)) {
                        pooledSnapshot = acquired
                        pooled = true
                    } else {
                        try { acquired.recycle() } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
                val snapshot = pooledSnapshot ?: source.copy(source.config, false)
                val snapshotForClosure = snapshot
                val pooledForClosure = pooled
                
                recognizer.process(InputImage.fromBitmap(snapshot, 0))
                    .addOnCompleteListener { task ->
                        try {
                            if (pooledForClosure) com.assistant.SnapshotBufferPool.release(snapshotForClosure)
                            else snapshotForClosure.recycle()
                        } catch (_: Throwable) {}
                        
                        if (task.isSuccessful) {
                            val visionText = task.result
                        val detectedText = visionText.textBlocks.asSequence()
                            .filterNot { com.assistant.vision.OverlaySelfMask.isSelfDrawnCapture(it.boundingBox) }
                            .joinToString(" ") { it.text }
                            .replace("\n", " ")

                        UiStateObserver.observeText(detectedText)
                        com.assistant.vision.OverlaySelfMask.tickAndLog()

                        if (detectedText.isNotBlank() && detectedText != lastLoggedOcrText) {
                            lastLoggedOcrText = detectedText
                            RuntimeMetricsRegistry.ocrDetections.incrementAndGet()
                            RuntimeLogger.log("OCR: ${detectedText.take(120)}", "OCR")
                        }

                        if (
                            detectedText.isNotBlank() &&
                            !detectedText.contains("SPLENDOR ASSIST", true) &&
                            !detectedText.contains("Runtime Summary", true) &&
                            !detectedText.contains("Runtime Nodes", true) &&
                            !detectedText.contains("Start Engine", true) &&
                            !detectedText.contains("View Logs", true) &&
                            !detectedText.contains("Activate All Adapters", true) &&
                            !detectedText.contains("🕶️", true) &&
                            !detectedText.contains("ENGINE READY", true) &&
                            !detectedText.contains("BLOCKED:", true) &&
                            !detectedText.contains("Audit :", true) &&
                            !detectedText.contains("Verified :", true) &&
                            (
                                detectedText.contains("time", true) ||
                                detectedText.contains("match", true) ||
                                detectedText.contains("vs", true) ||
                                detectedText.contains("score", true)
                            ) &&
                            System.currentTimeMillis() - lastMatchDetectionTime >= 5000L
                        ) {
                            lastMatchDetectionTime = System.currentTimeMillis()
                            SmartAssistRepository.activatePanic()
                            val lv = com.assistant.LiveVectorResolver.resolve(
                                reusableBitmap?.width?.toFloat() ?: 1080f,
                                reusableBitmap?.height?.toFloat() ?: 2400f
                            )
                            val pipe = com.assistant.SmartAssistPipeline()
                            val vectorDx = lv.endX - lv.startX
                            val vectorDy = lv.endY - lv.startY
                            val vectorDistance = kotlin.math.hypot(vectorDx, vectorDy)
                            val dec = if (lv.hasRealData) {
                                pipe.computeOptimalVector(lv.startX, lv.startY, lv.endX, lv.endY, lv.duration)
                            } else {
                                null
                            }
                            // The OCR decision used to be only logged ("submitted" was
                            // just a boolean). Route it into the bus when shouldAct:
                            // the bus still gates it (reservations, UiExecutionGate),
                            // and the 5s match-detection cooldown above limits the rate.
                            val submitted = if (dec?.shouldAct == true) {
                                com.assistant.execution.HybridExecutionTerminal.route(pipe.createExecutionRequest(dec))
                            } else {
                                false
                            }
                            RuntimeLogger.log(
                                "SMART_ASSIST_GATE real=${lv.hasRealData} distance=${vectorDistance.toInt()} duration=${lv.duration} action=${dec?.actionType ?: "NO_REAL_DATA"} shouldAct=${dec?.shouldAct ?: false} submitted=$submitted",
                                "SMART_ASSIST"
                            )
                            RuntimeMetricsRegistry.matchDetections.incrementAndGet()
                            // No63-D19: recording/saved parameters removed
                            // — no recording/saved state exists in the repo,
                            // so they were always false here and their text
                            // segments could never appear.
                            RuntimeNotificationCoordinator.update(
                                context = applicationContext,
                                antiban = true,
                                matchDetected = true
                            )
                            RuntimeLogger.log("SMART_ASSIST_GATE match detected, overlay active", "SMART_ASSIST")
                            updateOverlayVisuals("🕶️", Color.GREEN)
                            // Delayed empty runnable removed
                        }
                    }
                }
            } finally {
                taskExecutionLock.unlock()
            }
        }
    }

    private fun initializeProcessingEngine() {
        isRunning = true
    }

    private fun startTrajectoryWatchdog() {
        trajectoryRunnable = object : Runnable {
            override fun run() {
                if (!::overlayView.isInitialized) return
                val panicActive = SmartAssistRepository.panicActive()
                if (lastPanicState != panicActive) {
                    lastPanicState = panicActive
                    // overlayView MUST remain fully transparent to prevent MediaProjection pixel contamination.
                    overlayView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // Never paint app-owned pixels (red indicator) into the capture surface
                    panicIndicator?.visibility = View.GONE
                }
                trajectoryHandler.postDelayed(this, 250L)
            }
        }
        trajectoryHandler.post(trajectoryRunnable!!)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            if (taskExecutionLock.tryLock()) {
                try {
                    reusableBitmap?.recycle()
                    reusableBitmap = null
                } finally {
                    taskExecutionLock.unlock()
                }
            }
        }
    }

    override fun onDestroy() {
        // Tear down both surfaces so engine shutdown cannot leave a stale
        // control surface floating over the game.
        modMenuController?.hideLauncher()
        modMenuController?.hide()
        modMenuController = null
        // No66-D19: stop the interception bridge next to the ModMenu
        // teardown — the bridge's collector writes the registry for the
        // life of the process unless stop() cancels its scope, so a
        // START_STICKY stop/start cycle used to leave the old collector
        // running beside the new one.
        interceptionBridge?.stop()
        interceptionBridge = null
        SmartAssistRepository.clearPanic()
        // No62-D7: clearAll() covers every published tag (hud, modmenu,
        // any future publisher) instead of only "hud" — the mask must
        // not outlive the service under any tag. clearPrefix("hud") is
        // subsumed: clearAll() clears each key through clear(), which
        // is what clearPrefix did per key.
        com.assistant.vision.OverlaySelfMask.clearAll()
        // L4 (batch 1): teardown attribution — the service destroy is a
        // system teardown event; record it BEFORE shutdown() so the
        // TEARDOWN line can correlate this shutdown to the destroy.
        com.assistant.TeardownAttributionEngine.noteServiceDestroy()
        com.assistant.RuntimeCoordinator.shutdown(caller = "OverlayService.onDestroy")
        OverlaySurvivalEngine.destroyed()

        isRunning = false
        try { notificationManager.cancel(1102) } catch (_: Throwable) {}
        try { notificationManager.cancel(1103) } catch (_: Throwable) {}

        trajectoryRunnable?.let { trajectoryHandler.removeCallbacks(it) }
        trajectoryRunnable = null
        keepAliveRunnable?.let { keepAliveHandler.removeCallbacks(it) }
        keepAliveRunnable = null

        if (::overlayView.isInitialized) {
            globalLayoutListener?.let {
                try { overlayView.viewTreeObserver.removeOnGlobalLayoutListener(it) } catch (_: Throwable) {}
            }
            try { windowManager.removeViewImmediate(overlayView) } catch (t: Throwable) {
                try { RuntimeLogger.log("CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message, "FAULT") } catch (_: Throwable) {}
            }
        }
        globalLayoutListener = null

        processingThread?.interrupt()
        processingThread = null
        visionScope.cancel()

        teardownCaptureResources(CaptureState.IDLE)

        try { recognizer.close() } catch (_: Throwable) {}
        reusableBitmap?.recycle()
        reusableBitmap = null

        ChoreographerRenderLoop.stop()

        ocrIoThread?.quitSafely()
        ocrIoThread = null

        
        // V53: Release hardware locks
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {}

        // SPLENDOR_P6_ZOMBIE_CLEANUP_BEGIN
        // The self-heal agent is started in onCreate but was never stopped
        // here, and the static instance was never cleared: after stopSelf
        // the agent kept running against a destroyed service (session-2
        // zombie: 5,205x COLLECT_ZERO over 2h25m). Stop the agent and clear
        // the instance so later captureState()/deliver() calls cannot act
        // on a dead service. onCreate re-inits both on the next start.
        try { com.assistant.RuntimeSelfHealEngine.stop() } catch (_: Throwable) {}
        instance = null
        // SPLENDOR_P6_ZOMBIE_CLEANUP_END

        super.onDestroy()
    }
}


package com.assistant

import com.assistant.diagnostic.DefectEscalationBus

import android.annotation.SuppressLint
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.RuntimeMetricsRegistry
import com.assistant.adapter.smartassist.SmartAssistRepository
import com.assistant.survival.OverlaySurvivalEngine
import com.assistant.overlay.metrics.SmartAssistMetrics
import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import com.assistant.overlay.notification.RuntimeNotificationCoordinator
import com.assistant.overlay.runtime.PerformanceGovernor

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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.PerformanceHintManager
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.assistant.adapter.interruption.CallOverlayRepository
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class OverlayService : Service(), ComponentCallbacks2 {

    // PHASE17_RUNTIME_GUARDS
    @Volatile
    private var runtimeInitialized = false


    companion object {
        private const val CHANNEL_ID = "efootball_assistant_channel"
        private const val NOTIFICATION_ID = 101
        // PHASE5B: direct nullable reference, cleared in onDestroy.
        // WeakRef was unreliable under memory pressure (GC nulls it exactly
        // when recovery is most needed). Direct ref is safe here because
        // we clear it in onDestroy(), preventing leaks.
        @Volatile var instance: OverlayService? = null
            private set
        @JvmStatic
        fun restartCaptureIfAlive(): Boolean =
            instance?.restartCapture() ?: false
    }

    private var isRunning = false
    private var processingThread: Thread? = null
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var txtEngineStatus: TextView
    private lateinit var notificationManager: NotificationManager

    // 🕶️ is a separate touchable window. The main HUD remains
    // FLAG_NOT_TOUCHABLE so game touches are not intercepted.
    private var lagGlassView: TextView? = null
    private val glassTapHandler = Handler(Looper.getMainLooper())
    private var glassTapPending = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    // A MediaProjection session becomes invalid after Callback.onStop().
    // This flag prevents the self-heal path from trying to reuse a revoked
    // projection token.
    @Volatile
    private var projectionRevoked = false



    private var perfHintSession: PerformanceHintManager.Session? = null
    private var ocrIoThread: android.os.HandlerThread? = null
    private var ocrIoHandler: android.os.Handler? = null
    private var lastOcrTime = 0L
    private var lastMatchDetectionTime = 0L
    private val OCR_INTERVAL_MS = 1500L 
    private var reusableBitmap: Bitmap? = null
    private val taskExecutionLock = ReentrantLock()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    // PHASE4B: 30fps HYBRID gate
    // 33ms = 30fps capture rate. Every frame: cheap ball-only scan stamps VisionTrust.
    // Every 2nd frame: full VisionCore (58 engines) = 15fps compute cost.
    // Result: 30fps ball tracking accuracy + 15fps engine load on Helio G81-Ultra.
    @Volatile private var lastFrameProcessedMs = 0L
    // Base frame interval. Actual interval is adaptive — see MemoryCaptureGateEngine.
    private val captureFrameIntervalBase = 33L  // 30fps base
    // Adaptive interval: reads MemoryCaptureGateEngine tier each frame gate check.
    // CRITICAL=100ms, PRESSURE=66ms, WATCH=50ms, HEALTHY=33ms.
    private val captureFrameIntervalMs: Long
        get() = com.assistant.adapter.memory.MemoryCaptureGateEngine.recommendedIntervalMs()
    @Volatile private var captureFrameCount = 0L  // alternating full/light processing

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * PHASE4B: Agent capture restart.
     * Called when RuntimeSelfHealEngine detects capture thread death.
     * Recreates the ImageReader + VirtualDisplay using the existing
     * mediaProjection (valid until revoked by the OS).
     * Returns true if restart was attempted, false if projection is gone.
     */
    fun restartCapture(): Boolean {
        // A revoked MediaProjection cannot be reused. A fresh user-authorized
        // projection session is required.
        if (projectionRevoked) {
            RuntimeLogger.log(
                "AGENT CAPTURE RESTART: projection already revoked; " +
                    "fresh MediaProjection authorization required",
                "AGENT"
            )
            return false
        }

        if (mediaProjection == null) {
            RuntimeLogger.log(
                "AGENT CAPTURE RESTART: no active MediaProjection",
                "AGENT"
            )
            return false
        }

        try {
            RuntimeLogger.log("AGENT CAPTURE RESTART: attempting ImageReader recreation", "AGENT")
            // Drain the OCR handler queue before closing the reader.
            // Any pending onImageAvailable callback holds an Image reference that
            // becomes invalid the instant close() is called. The latch ensures
            // the drain completes before we proceed.
            try {
                val drainLatch = java.util.concurrent.CountDownLatch(1)
                ocrIoHandler?.post { drainLatch.countDown() } ?: drainLatch.countDown()
                drainLatch.await(100L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {}
            // Release old reader only after handler queue is drained
            try { virtualDisplay?.release() } catch (_: Throwable) {}
            try { imageReader?.close() } catch (_: Throwable) {}
            // Re-setup with fresh ImageReader (same dimensions as before)
            setupMediaProjection(android.app.Activity.RESULT_OK,
                com.assistant.EngineData.intent ?: return false)
            lastFrameProcessedMs = 0L
            captureFrameCount = 0L
            RuntimeLogger.log("AGENT CAPTURE RESTART: ImageReader recreated successfully", "AGENT")
            return true
        } catch (e: Exception) {
            RuntimeLogger.log("AGENT CAPTURE RESTART FAILED: ${e.message}", "AGENT")
            return false
        }
    }

    
override fun onCreate() {

        if(runtimeInitialized){
            return
        }

        runtimeInitialized=true

        super.onCreate()
        RuntimeLogger.log("OverlayService started", "OVERLAY")
        com.assistant.vision.ForegroundGate.install(application)
        // PHASE5: init + START agent immediately (3s grace built-in)
        try {
            com.assistant.adapter.smartassist.RuntimeSelfHealEngine.init(applicationContext)
            com.assistant.adapter.smartassist.RuntimeSelfHealEngine.start()
        } catch (_: Throwable) {}
        instance = this
        // Anti-Cheat defense disabled to prevent HyperOS false-positive kill
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        initializePerformanceMode()
        ocrIoThread = android.os.HandlerThread("OverlayOCRThread", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        ocrIoHandler = android.os.Handler(ocrIoThread!!.looper)
        initializeOverlayUI()
    }

    private fun initializePerformanceMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val hintManager = getSystemService(PerformanceHintManager::class.java)
                perfHintSession = hintManager?.createHintSession(intArrayOf(Process.myTid()), 33333333L)  // PHASE4B: 30fps hybrid target
            } catch (e: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("CROSS_PROCESS_CODE", EngineData.code) ?: EngineData.code
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("CROSS_PROCESS_DATA", Intent::class.java) ?: EngineData.intent
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("CROSS_PROCESS_DATA") ?: EngineData.intent
        }
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            startForegroundSafely()

            try {
                setupMediaProjection(resultCode, data)
                if (!isRunning) {
                    initializeProcessingEngine()
                }
            } catch (e: Exception) {
                logSilentFailure(e)
                stopSelf()
            }
        } else {
            logSilentFailure(Exception("Intent Data Null or Result Code Invalid: $resultCode"))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun logSilentFailure(e: Exception) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val logFile =
                com.assistant.storage.SplendorStorageRoot.file(
                    "Splendor_Crash_Reports.txt"
                )
            FileWriter(logFile, true).use { writer ->
                PrintWriter(writer).use { pw ->
                    pw.println("=== SILENT ENGINE FAULT: $timestamp ===")
                    e.printStackTrace(pw)
                    pw.println("=========================================\n")
                }
            }
        } catch (ignored: Exception) {}
    }

    private fun startForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Engine Primary", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Splendor Assist Locked")
            .setContentText("Engine Active")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("InflateParams")
    private fun initializeOverlayUI() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(com.assistant.overlay.R.layout.overlay_layout, null)
        txtEngineStatus = overlayView.findViewById(com.assistant.overlay.R.id.overlay_status_text)
        
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlayView, layoutParams)

        installDefectGlass()

        // GAP1B-A: report the leaves we paint so capture/OCR ignores them.
        // Re-published on every layout pass, so status-text resizes stay masked.
        overlayView.post {
            com.assistant.vision.OverlaySelfMask.publishHierarchy("hud", overlayView)
        }
        overlayView.viewTreeObserver.addOnGlobalLayoutListener {
            com.assistant.vision.OverlaySelfMask.publishHierarchy("hud", overlayView)
        }
        OverlaySurvivalEngine.attached()
        updateOverlayVisuals("GUARD LOCK: SECURE [ANTI-BAN ON]", Color.GREEN)
        startTrajectoryWatchdog(
            overlayView,
            Handler(Looper.getMainLooper())
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDefectGlass() {
        if (lagGlassView != null) return

        val glass = TextView(this).apply {
            text = "🕶️"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(165, 15, 15, 15))
            contentDescription =
                "Splendor Assist defect escalation: GLASS_SINGLE=PERFORMANCE, GLASS_DOUBLE=GAMEPLAY"
            isClickable = true
            isFocusable = false

            setOnClickListener {
                if (glassTapPending) {
                    glassTapPending = false
                    glassTapHandler.removeCallbacksAndMessages(null)

                    // DOUBLE TAP = GAMEPLAY / adapter_smartassist
                    animate()
                        .rotationBy(720f)
                        .setDuration(700L)
                        .withEndAction {
                            rotation = 0f
                        }
                        .start()

                    try {
                        com.assistant.adapter.smartassist.RuntimeSelfHealEngine
                            .runManualGameplayEscalation()

                        RuntimeLogger.log(
                            "🕶️ DOUBLE TAP ACCEPTED: GAMEPLAY escalation -> " +
                                "adapter_smartassist + RuntimeSelfHealEngine",
                            "DEFECT_ESCALATION"
                        )

                        updateOverlayVisuals(
                            "GAMEPLAY DEFECT ESCALATION",
                            Color.YELLOW
                        )
                    } catch (t: Throwable) {
                        RuntimeLogger.log(
                            "🕶️ DOUBLE TAP FAILED: " +
                                "${t.javaClass.simpleName}: ${t.message}",
                            "DEFECT_ESCALATION"
                        )
                    }
                } else {
                    glassTapPending = true

                    glassTapHandler.postDelayed({
                        if (!glassTapPending) return@postDelayed

                        glassTapPending = false

                        // ONE TAP = PERFORMANCE ADAPTER FAMILY
                        animate()
                            .rotationBy(360f)
                            .setDuration(550L)
                            .withEndAction {
                                rotation = 0f
                            }
                            .start()

                        try {
                            DefectEscalationBus.publishPerformance(
                                "GLASS_SINGLE"
                            )

                            RuntimeLogger.log(
                                "🕶️ SINGLE TAP ACCEPTED: PERFORMANCE " +
                                    "defect broadcast to adapter performance family",
                                "DEFECT_ESCALATION"
                            )

                            updateOverlayVisuals(
                                "PERFORMANCE DEFECT ESCALATION",
                                Color.YELLOW
                            )
                        } catch (t: Throwable) {
                            RuntimeLogger.log(
                                "🕶️ SINGLE TAP FAILED: " +
                                    "${t.javaClass.simpleName}: ${t.message}",
                                "DEFECT_ESCALATION"
                            )
                        }
                    }, 350L)
                }
            }
        }

        lagGlassView = glass

        val density = resources.displayMetrics.density
        val size = (56f * density).toInt().coerceAtLeast(48)

        val lp = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8f * density).toInt()
            y = (96f * density).toInt()
        }

        try {
            windowManager.addView(glass, lp)

            RuntimeLogger.log(
                "🕶️ defect escalation control attached; " +
                    "single=PERFORMANCE double=GAMEPLAY",
                "OVERLAY"
            )
        } catch (t: Throwable) {
            lagGlassView = null
            RuntimeLogger.log(
                "🕶️ defect control attach failed: " +
                    "${t.javaClass.simpleName}: ${t.message}",
                "OVERLAY"
            )
        }
    }

    private fun removeDefectGlass() {
        try {
            lagGlassView?.let { view ->
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            }
        } catch (_: Throwable) {
        } finally {
            lagGlassView = null
            glassTapPending = false
            glassTapHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun updateOverlayVisuals(text: String, color: Int) {
        Handler(Looper.getMainLooper()).post {
            txtEngineStatus.text =
                if (CallOverlayRepository.incomingCallVisible)
                    "[CALL PROTECTED] " + text
                else
                    text
            txtEngineStatus.setTextColor(color)
        }
    }

    /**
     * Requests a completely new MediaProjection authorization session.
     *
     * The old projection token cannot be reused after Callback.onStop().
     * MainActivity owns the Android user-consent launcher and is therefore
     * responsible for obtaining the new resultCode + Intent.
     */
    private fun requestFreshProjectionAuthorization() {
        try {
            val recoveryIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra("REQUEST_MEDIA_PROJECTION_RECOVERY", true)
            }

            startActivity(recoveryIntent)

            RuntimeLogger.log(
                "MediaProjection recovery: MainActivity launched for fresh authorization",
                "AGENT"
            )
        } catch (t: Throwable) {
            RuntimeLogger.log(
                "MediaProjection recovery launch failed: " +
                    "${t.javaClass.simpleName}: ${t.message}",
                "AGENT"
            )
        }
    }

    private fun setupMediaProjection(code: Int, intent: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        projectionRevoked = false

        mediaProjection = projectionManager.getMediaProjection(code, intent)

        if (mediaProjection == null) {
            RuntimeLogger.log(
                "MediaProjection setup failed: getMediaProjection returned null",
                "OVERLAY"
            )
            throw IllegalStateException("MediaProjection unavailable")
        }
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()

                // MediaProjection.Callback.onStop() means this projection
                // session is no longer valid. Do NOT call restartCapture()
                // here because restartCapture() intentionally reuses the
                // existing projection and that token has already been revoked.
                projectionRevoked = true

                Handler(Looper.getMainLooper()).post {
                    try {
                        virtualDisplay?.release()
                    } catch (_: Throwable) {
                    }

                    try {
                        imageReader?.close()
                    } catch (_: Throwable) {
                    }

                    virtualDisplay = null
                    imageReader = null
                    mediaProjection = null
                    lastFrameProcessedMs = 0L
                    captureFrameCount = 0L

                    RuntimeLogger.log(
                        "MediaProjection.onStop(): projection revoked; " +
                            "capture resources invalidated; fresh authorization required",
                        "OVERLAY"
                    )

                    requestFreshProjectionAuthorization()
                }
            }
        }
        mediaProjection?.registerCallback(projectionCallback!!, Handler(Looper.getMainLooper()))
        val scale = 0.4f
        val metrics = DisplayMetrics()
        val finalWidth: Int
        val finalHeight: Int
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
        // GAP1B-B: map screen coords -> captured-image coords
        com.assistant.vision.OverlaySelfMask.setCaptureScale(
            finalWidth,
            finalHeight,
            if (scale > 0f) (finalWidth / scale).toInt() else finalWidth,
            if (scale > 0f) (finalHeight / scale).toInt() else finalHeight
        )
        imageReader = ImageReader.newInstance(finalWidth, finalHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            // PHASE4B: 30fps hybrid gate — 33ms = 30fps; alternating full/light frames
            val captureNow = System.currentTimeMillis()
            if (captureNow - lastFrameProcessedMs < captureFrameIntervalMs) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameProcessedMs = captureNow
            val thisFrameCount = ++captureFrameCount
            val doFullProcessing = (thisFrameCount % 2L == 0L)  // full every 2nd frame
            // GAP1C: our own Control Room / Diagnosis screens are not game truth
            if (com.assistant.vision.ForegroundGate.shouldSkipCapture()) {
                image.close()
                return@setOnImageAvailableListener
            }
            try {

                val scanBuffer = image.planes[0].buffer.duplicate()

                val normalized =
                    com.assistant.adapter.smartassist.FrameNormalizer.normalize(
                        scanBuffer.duplicate(),
                        image.width,
                        image.height
                    )

                if (doFullProcessing) {
                    // ─── FULL FRAME (15fps): all 58 engines + 38 contributors ───
                    val state =
                        com.assistant.adapter.smartassist.VisionCore.process(normalized)
                    com.assistant.BoosterIgnition.ensureIgnited(this)
                    com.assistant.AppContributorRegistration.ensureRegistered()
                    com.assistant.adapter.smartassist.RuntimeCoordinator.reportCaptureReady()
                    val frame =
                        com.assistant.adapter.smartassist.FrameAssembler.assemble()
                    com.assistant.adapter.smartassist.RuntimeDecisionLoop.onFrame(frame)
                    com.assistant.adapter.smartassist.GameStateBuilder.update(state)
                    com.assistant.overlay.interceptor.OmnipotentGoalkeeperEngine
                        .scanFrameForOpponentAnimation(scanBuffer, image.width, image.height)
                } else {
                    // ─── LIGHT FRAME (30fps alt): ball-only scan → stamps VisionTrust ───
                    // Keeps ballTrust fresh between full frames so trust never expires.
                    // At 15fps without this, trust decays between full frames (FRESH_MS=200ms
                    // at 66ms intervals = only 3 full frames before decay starts).
                    try {
                        val lightSamples =
                            com.assistant.adapter.smartassist.FrameScanner.scan(normalized)
                        val lightBlobs =
                            com.assistant.adapter.smartassist.ConnectedComponentEngine.extract(lightSamples)
                        val filteredBlobs =
                            com.assistant.adapter.smartassist.NoiseFilter.filter(lightBlobs)
                        val ballCandidate =
                            com.assistant.adapter.smartassist.BallCandidateEngine.select(filteredBlobs)
                        val ball =
                            com.assistant.adapter.smartassist.BallDetector.detect(ballCandidate)
                        // Stamp trust so it stays fresh until next full frame
                        com.assistant.adapter.smartassist.BallTelemetryBridge.publish(ball)
                    } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                // Errors (StackOverflowError, OOM) are NOT Exceptions and used to
                // escape here, killing the capture thread and the whole process.
                try {
                    RuntimeLogger.log(
                        "CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message,
                        "FAULT"
                    )
                } catch (_: Throwable) {}
            }
            val shedFactor = when (com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.currentLoadShed()) {
                "HEAVY" -> 4L
                "LIGHT" -> 2L
                else -> 1L
            }
            if (System.currentTimeMillis() - lastOcrTime >= OCR_INTERVAL_MS * shedFactor) {
                lastOcrTime = System.currentTimeMillis()
                processImageForOCR(image)
            } else {
                image.close()
            }
        }, ocrIoHandler ?: Handler(Looper.getMainLooper()))
        virtualDisplay = mediaProjection?.createVirtualDisplay("HybridCoachScreen", finalWidth, finalHeight, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, imageReader?.surface, null, null)
    }

    private fun processImageForOCR(image: Image) {
        // Guard against closed images from a previous ImageReader generation.
        // Race: restartCapture() calls imageReader?.close() which invalidates
        // images acquired before close() was called. Any pending callback that
        // arrives after close() carries a dead image.
        try {
            image.width  // throwISEIfImageIsInvalid — safe canary touch
        } catch (_: IllegalStateException) {
            try { image.close() } catch (_: Throwable) {}
            return
        }
        if (taskExecutionLock.tryLock()) {
            try {
                if (reusableBitmap == null || reusableBitmap!!.width != image.width || reusableBitmap!!.height != image.height) {
                    reusableBitmap?.recycle()
                    reusableBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                }
                reusableBitmap!!.copyPixelsFromBuffer(image.planes[0].buffer)

                try {
                    
                val scanBuffer = image.planes[0].buffer.duplicate()

                val normalized =
                    com.assistant.adapter.smartassist.FrameNormalizer.normalize(
                        scanBuffer.duplicate(),
                        image.width,
                        image.height
                    )

                val state =
                    com.assistant.adapter.smartassist.VisionCore.process(
                        normalized
                    )

                com.assistant.adapter.smartassist.GameStateBuilder.update(
                    state
                )

                    com.assistant.overlay.interceptor.OmnipotentGoalkeeperEngine
                        .scanFrameForOpponentAnimation(
                            scanBuffer,
                            image.width,
                            image.height
                        )
                } catch (t: Throwable) {
                // Errors (StackOverflowError, OOM) are NOT Exceptions and used to
                // escape here, killing the capture thread and the whole process.
                try {
                    RuntimeLogger.log(
                        "CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message,
                        "FAULT"
                    )
                } catch (_: Throwable) {}
            }

                recognizer.process(InputImage.fromBitmap(reusableBitmap!!, 0))
                    .addOnSuccessListener { visionText ->

                        val detectedText =
                            visionText.textBlocks
                                .asSequence()
                                .filterNot {
                                    com.assistant.vision.OverlaySelfMask
                                        .isSelfDrawnCapture(it.boundingBox)
                                }
                                .joinToString(" ") { it.text }
                                .replace("\n", " ")
                                .take(120)

                        com.assistant.vision.OverlaySelfMask.tickAndLog()

                        if (detectedText.isNotBlank()) {
                            RuntimeMetricsRegistry
                                .ocrDetections
                                .incrementAndGet()

                            RuntimeLogger.log(
                                "OCR: $detectedText",
                                "OCR"
                            )
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

                            SmartAssistRepository.activatePanic()

                            

                            

                            val lv = com.assistant.adapter.smartassist.LiveVectorResolver.resolve(
                                reusableBitmap?.width?.toFloat() ?: 1080f,
                                reusableBitmap?.height?.toFloat() ?: 2400f
                            )
                            val pipe = com.assistant.adapter.smartassist.SmartAssistPipeline()
                            val vectorDx = lv.endX - lv.startX
                            val vectorDy = lv.endY - lv.startY
                            val vectorDistance = kotlin.math.hypot(vectorDx, vectorDy)
                            val dec = if (lv.hasRealData) {
                                pipe.computeOptimalVector(
                                    lv.startX,
                                    lv.startY,
                                    lv.endX,
                                    lv.endY,
                                    lv.duration
                                )
                            } else {
                                null
                            }
                            // Task 4: OverlayService no longer offers directly.
                            // The six registered contributors are the sole gameplay
                            // producers, driven once per frame by RuntimeDecisionLoop
                            // (invoked in this same capture block). This ends the
                            // duplicate-producer flood (the frozen 406/83 SHOT spam).
                            val submitted = dec?.shouldAct == true
                            RuntimeLogger.log(
                                "SMART_ASSIST_GATE real=${lv.hasRealData} " +
                                    "distance=${vectorDistance.toInt()} duration=${lv.duration} " +
                                    "action=${dec?.actionType ?: "NO_REAL_DATA"} " +
                                    "shouldAct=${dec?.shouldAct ?: false} submitted=$submitted",
                                "SMART_ASSIST"
                            )

                            RuntimeMetricsRegistry
                                .matchDetections
                                .incrementAndGet()



                            RuntimeNotificationCoordinator.update(
                                context = applicationContext,
                                antiban = true,
                                matchDetected = true,
                                recording = false,
                                saved = false
                            )

                            RuntimeLogger.log(
                                "🕶️",
                                "SMART_ASSIST"
                            )

                            updateOverlayVisuals(
                                "🕶️",
                                Color.GREEN
                            )

                            Handler(Looper.getMainLooper()).postDelayed({
                                                            }, 3000)
                        }
                    }

            } finally {
                taskExecutionLock.unlock(); try { image.close() } catch(e:Exception){}
            }
        } else {
            image.close()
        }
    }

    private fun initializeProcessingEngine() {
        isRunning = true
        processingThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            while (isRunning) {

                try {


                    Thread.sleep(33)

                } catch (e: InterruptedException) {

                    break
                }
            }
        }.apply { start() }
    }

    
    

override fun onDestroy() {
        removeDefectGlass()
        com.assistant.vision.OverlaySelfMask.clearPrefix("hud")
        com.assistant.adapter.smartassist.RuntimeCoordinator.shutdown()
        OverlaySurvivalEngine.destroyed()
        isRunning = false
        // PHASE10_PANIC_PERSISTENCE_KEEP_STATE
        try { windowManager.removeViewImmediate(overlayView) } catch (t: Throwable) {
                // Errors (StackOverflowError, OOM) are NOT Exceptions and used to
                // escape here, killing the capture thread and the whole process.
                try {
                    RuntimeLogger.log(
                        "CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message,
                        "FAULT"
                    )
                } catch (_: Throwable) {}
            }
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (t: Throwable) {
                // Errors (StackOverflowError, OOM) are NOT Exceptions and used to
                // escape here, killing the capture thread and the whole process.
                try {
                    RuntimeLogger.log(
                        "CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message,
                        "FAULT"
                    )
                } catch (_: Throwable) {}
            }
        try { projectionCallback?.let { mediaProjection?.unregisterCallback(it) } } catch (t: Throwable) {
                // Errors (StackOverflowError, OOM) are NOT Exceptions and used to
                // escape here, killing the capture thread and the whole process.
                try {
                    RuntimeLogger.log(
                        "CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message,
                        "FAULT"
                    )
                } catch (_: Throwable) {}
            }

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }
}



// [SECURITY GUARD LOCK ACTIVE]
// TASK 1, 5, 6: AI BLUE TRACE ENGINE & TRAJECTORY RENDERER
fun startTrajectoryWatchdog(overlayView: android.view.View, handler: android.os.Handler) {
    val renderRunnable = object : java.lang.Runnable {
        override fun run() {
            val panicActive =
            SmartAssistRepository.panicActive() &&
            System.currentTimeMillis() -
            0L <= 3000L

            if (!panicActive && SmartAssistRepository.panicActive()) {
                // PHASE10_PANIC_PERSISTENCE_KEEP_STATE
            }

            if (panicActive) {
                overlayView.setBackgroundColor(android.graphics.Color.argb(50, 255, 0, 0))
            } else {
                overlayView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            handler.postDelayed(this, 100L)
        }
    }
    handler.post(renderRunnable)
}

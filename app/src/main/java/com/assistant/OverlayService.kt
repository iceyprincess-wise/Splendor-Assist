package com.assistant

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

    @Volatile
    private var runtimeInitialized = false

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
            instance?.projectionRevoked ?: true

        @JvmStatic
        fun requestRecoveryPrompt() {
            instance?.showCaptureRecoveryPrompt()
        }
    }

    private var isRunning = false
    private var processingThread: Thread? = null
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var txtEngineStatus: TextView
    private lateinit var notificationManager: NotificationManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile
    private var projectionRevoked = false
    @Volatile
    private var recoveryPromptShown = false
    private var recoveryPromptView: TextView? = null

    private var perfHintSession: PerformanceHintManager.Session? = null
    private var ocrIoThread: android.os.HandlerThread? = null
    private var ocrIoHandler: android.os.Handler? = null
    private var lastOcrTime = 0L
    private var lastMatchDetectionTime = 0L
    private val OCR_INTERVAL_MS = 1500L 
    private var reusableBitmap: Bitmap? = null
    private val taskExecutionLock = ReentrantLock()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    @Volatile private var lastFrameProcessedMs = 0L
    private val captureFrameIntervalBase = 33L
    private val captureFrameIntervalMs: Long
        get() = com.assistant.adapter.memory.MemoryCaptureGateEngine.recommendedIntervalMs()
    @Volatile private var captureFrameCount = 0L
    @Volatile private var lastAppliedPanicState: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    fun restartCapture(): Boolean {
        if (projectionRevoked) {
            RuntimeLogger.log("AGENT CAPTURE RESTART: projection already revoked; fresh MediaProjection authorization required", "AGENT")
            return false
        }
        if (mediaProjection == null) {
            RuntimeLogger.log("AGENT CAPTURE RESTART: no active MediaProjection", "AGENT")
            return false
        }
        try {
            RuntimeLogger.log("AGENT CAPTURE RESTART: attempting ImageReader recreation", "AGENT")
            try {
                val drainLatch = java.util.concurrent.CountDownLatch(1)
                ocrIoHandler?.post { drainLatch.countDown() } ?: drainLatch.countDown()
                drainLatch.await(100L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {}
            try { virtualDisplay?.release() } catch (_: Throwable) {}
            try { imageReader?.close() } catch (_: Throwable) {}
            setupMediaProjection(android.app.Activity.RESULT_OK, com.assistant.EngineData.intent ?: return false)
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
        if(runtimeInitialized) return
        runtimeInitialized=true
        super.onCreate()
        RuntimeLogger.log("OverlayService started", "OVERLAY")
        com.assistant.vision.ForegroundGate.install(application)
        try {
            com.assistant.adapter.smartassist.RuntimeSelfHealEngine.init(applicationContext)
            com.assistant.adapter.smartassist.RuntimeSelfHealEngine.start()
        } catch (_: Throwable) {}
        try { com.assistant.adapter.smartassist.CaptaincySkillEngine.init(applicationContext) } catch (_: Throwable) {}
        try { com.assistant.adapter.smartassist.CrowdingZoneDetector.init(applicationContext) } catch (_: Throwable) {}
        instance = this
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        initializePerformanceMode()
        ocrIoThread = android.os.HandlerThread("OverlayOCRThread", android.os.Process.THREAD_PRIORITY_DEFAULT).apply { start() }
        ocrIoHandler = android.os.Handler(ocrIoThread!!.looper)
        initializeOverlayUI()
    }

    private fun initializePerformanceMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val hintManager = getSystemService(PerformanceHintManager::class.java)
                perfHintSession = hintManager?.createHintSession(intArrayOf(Process.myTid()), 33333333L)
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
            val logFile = com.assistant.storage.SplendorStorageRoot.file("Splendor_Crash_Reports.txt")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

        overlayView.post {
            com.assistant.vision.OverlaySelfMask.publishHierarchy("hud", overlayView)
        }
        overlayView.viewTreeObserver.addOnGlobalLayoutListener {
            com.assistant.vision.OverlaySelfMask.publishHierarchy("hud", overlayView)
        }
        OverlaySurvivalEngine.attached()
        updateOverlayVisuals("GUARD LOCK: SECURE [ANTI-BAN ON]", Color.GREEN)
        startTrajectoryWatchdog(overlayView, Handler(Looper.getMainLooper()))
    }

    private fun updateOverlayVisuals(text: String, color: Int) {
        Handler(Looper.getMainLooper()).post {
            txtEngineStatus.text = if (CallOverlayRepository.incomingCallVisible) "[CALL PROTECTED] " + text else text
            txtEngineStatus.setTextColor(color)
        }
    }

    private fun requestFreshProjectionAuthorization() {
        try {
            val recoveryIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("REQUEST_MEDIA_PROJECTION_RECOVERY", true)
            }
            startActivity(recoveryIntent)
            RuntimeLogger.log("MediaProjection recovery: MainActivity launched for fresh authorization", "AGENT")
        } catch (t: Throwable) {
            RuntimeLogger.log("MediaProjection recovery launch failed: ${t.javaClass.simpleName}: ${t.message}", "AGENT")
        }
    }

    /*
     * ROOT-CAUSE FIX (HealLog 2026-08-25): background startActivity is blocked by
     * Android 10+/HyperOS BAL rules, so a revoked projection never recovered.
     * The service already owns an overlay window: show a TOUCHABLE banner plus a
     * high-priority notification. The user tap is the BAL exemption that legally
     * relaunches authorization; fresh token resumes capture via onStartCommand.
     */
    fun showCaptureRecoveryPrompt() {
        Handler(Looper.getMainLooper()).post {
            try {
                if (recoveryPromptShown) return@post
                recoveryPromptShown = true
                val prompt = TextView(this).apply {
                    text = "⚠️ CAPTURE STOPPED - TAP TO RESTORE"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(230, 180, 30, 30))
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
        Handler(Looper.getMainLooper()).post {
            recoveryPromptView?.let { v ->
                try { windowManager.removeViewImmediate(v) } catch (_: Throwable) {}
            }
            recoveryPromptView = null
            recoveryPromptShown = false
        }
    }

    private fun postRecoveryNotification() {
        try {
            val tapIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("REQUEST_MEDIA_PROJECTION_RECOVERY", true)
            }
            val pending = android.app.PendingIntent.getActivity(
                this, 1101, tapIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Splendor Assist: capture stopped")
                .setContentText("Tap to restore screen capture")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(1102, notification)
        } catch (t: Throwable) {
            RuntimeLogger.log("CAPTURE RECOVERY NOTIFICATION failed: ${t.javaClass.simpleName}", "AGENT")
        }
    }

    private fun setupMediaProjection(code: Int, intent: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionRevoked = false
        dismissCaptureRecoveryPrompt()
        mediaProjection = projectionManager.getMediaProjection(code, intent)

        if (mediaProjection == null) {
            RuntimeLogger.log("MediaProjection setup failed: getMediaProjection returned null", "OVERLAY")
            throw IllegalStateException("MediaProjection unavailable")
        }
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                projectionRevoked = true
                Handler(Looper.getMainLooper()).post {
                    try { virtualDisplay?.release() } catch (_: Throwable) {}
                    try { imageReader?.close() } catch (_: Throwable) {}
                    virtualDisplay = null
                    imageReader = null
                    mediaProjection = null
                    lastFrameProcessedMs = 0L
                    captureFrameCount = 0L
                    RuntimeLogger.log("MediaProjection.onStop(): projection revoked; capture resources invalidated; fresh authorization required", "OVERLAY")
                    requestFreshProjectionAuthorization()
                    showCaptureRecoveryPrompt()
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
        com.assistant.vision.OverlaySelfMask.setCaptureScale(finalWidth, finalHeight, if (scale > 0f) (finalWidth / scale).toInt() else finalWidth, if (scale > 0f) (finalHeight / scale).toInt() else finalHeight)
        imageReader = ImageReader.newInstance(finalWidth, finalHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val captureNow = System.currentTimeMillis()
            if (captureNow - lastFrameProcessedMs < captureFrameIntervalMs) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameProcessedMs = captureNow
            captureFrameCount++
            // V10 LATENCY FIX: Remove frame-skipping. Full processing MUST run every frame
            // to eliminate 33-66ms decision staleness. Memory pressure is handled by
            // MemoryCaptureGateEngine capping interval, not by skipping frames.
            if (com.assistant.vision.ForegroundGate.shouldSkipCapture()) {
                image.close()
                return@setOnImageAvailableListener
            }
            try {
                val scanBuffer = image.planes[0].buffer.duplicate()
                val normalized = com.assistant.adapter.smartassist.FrameNormalizer.normalize(scanBuffer.duplicate(), image.width, image.height)

                // V10 LATENCY FIX: Full processing runs every frame. Light path removed.
                val state = com.assistant.adapter.smartassist.VisionCore.process(normalized)
                com.assistant.BoosterIgnition.ensureIgnited(this)
                com.assistant.AppContributorRegistration.ensureRegistered()
                com.assistant.adapter.smartassist.RuntimeCoordinator.reportCaptureReady()
                val frame = com.assistant.adapter.smartassist.FrameAssembler.assemble()
                com.assistant.adapter.smartassist.RuntimeDecisionLoop.onFrame(frame)
                com.assistant.adapter.smartassist.GameStateBuilder.update(state)
                com.assistant.overlay.interceptor.OmnipotentGoalkeeperEngine.scanFrameForOpponentAnimation(scanBuffer, image.width, image.height)
            } catch (t: Throwable) {
                try { RuntimeLogger.log("CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message, "FAULT") } catch (_: Throwable) {}
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
        try {
            image.width
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

                recognizer.process(InputImage.fromBitmap(reusableBitmap!!, 0))
                    .addOnSuccessListener { visionText ->

                        val detectedText = visionText.textBlocks.asSequence()
                            .filterNot { com.assistant.vision.OverlaySelfMask.isSelfDrawnCapture(it.boundingBox) }
                            .joinToString(" ") { it.text }
                            .replace("\n", " ")
                            .take(120)

                        com.assistant.vision.OverlaySelfMask.tickAndLog()

                        if (detectedText.isNotBlank()) {
                            RuntimeMetricsRegistry.ocrDetections.incrementAndGet()
                            RuntimeLogger.log("OCR: $detectedText", "OCR")
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
                                pipe.computeOptimalVector(lv.startX, lv.startY, lv.endX, lv.endY, lv.duration)
                            } else {
                                null
                            }
                            
                            val submitted = dec?.shouldAct == true
                            RuntimeLogger.log(
                                "SMART_ASSIST_GATE real=${lv.hasRealData} distance=${vectorDistance.toInt()} duration=${lv.duration} action=${dec?.actionType ?: "NO_REAL_DATA"} shouldAct=${dec?.shouldAct ?: false} submitted=$submitted",
                                "SMART_ASSIST"
                            )

                            RuntimeMetricsRegistry.matchDetections.incrementAndGet()

                            RuntimeNotificationCoordinator.update(
                                context = applicationContext,
                                antiban = true,
                                matchDetected = true,
                                recording = false,
                                saved = false
                            )

                            RuntimeLogger.log("🕶️", "SMART_ASSIST")

                            updateOverlayVisuals("🕶️", Color.GREEN)

                            Handler(Looper.getMainLooper()).postDelayed({}, 3000)
                        }
                    }

            } finally {
                taskExecutionLock.unlock()
                try { image.close() } catch(e:Exception){}
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
                try { Thread.sleep(33) } catch (e: InterruptedException) { break }
            }
        }.apply { start() }
    }

    override fun onDestroy() {
        com.assistant.vision.OverlaySelfMask.clearPrefix("hud")
        com.assistant.adapter.smartassist.RuntimeCoordinator.shutdown()
        OverlaySurvivalEngine.destroyed()
        isRunning = false
        try { windowManager.removeViewImmediate(overlayView) } catch (t: Throwable) {
            try { RuntimeLogger.log("CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message, "FAULT") } catch (_: Throwable) {}
        }
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (t: Throwable) {
            try { RuntimeLogger.log("CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message, "FAULT") } catch (_: Throwable) {}
        }
        try { projectionCallback?.let { mediaProjection?.unregisterCallback(it) } } catch (t: Throwable) {
            try { RuntimeLogger.log("CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message, "FAULT") } catch (_: Throwable) {}
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

fun startTrajectoryWatchdog(overlayView: android.view.View, handler: android.os.Handler) {
    val renderRunnable = object : java.lang.Runnable {
        override fun run() {
            val isPanic = SmartAssistRepository.panicActive()
        if (isPanic != lastAppliedPanicState) {
            lastAppliedPanicState = isPanic
            if (isPanic) {
                overlayView.setBackgroundColor(android.graphics.Color.argb(50, 255, 0, 0))
            } else {
                overlayView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
            handler.postDelayed(this, 100L)
        }
    }
    handler.post(renderRunnable)
}

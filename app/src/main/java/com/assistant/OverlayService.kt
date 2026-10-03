package com.assistant

import android.annotation.SuppressLint
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.RuntimeMetricsRegistry
import com.assistant.SmartAssistRepository
import com.assistant.survival.OverlaySurvivalEngine
import com.assistant.overlay.metrics.SmartAssistMetrics
import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import com.assistant.overlay.notification.RuntimeNotificationCoordinator
import com.assistant.overlay.runtime.PerformanceGovernor
import com.assistant.memory.MmapStateEngine
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
import android.media.Image
import android.media.ImageReader
import java.nio.ByteBuffer
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
import android.view.ViewTreeObserver
import android.widget.TextView
import com.assistant.CallOverlayRepository
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
    private var processingThread: Thread? = null
    @Volatile private var reusableVisionBuffer: java.nio.ByteBuffer? = null
    @Volatile private var previousVisionBuffer: java.nio.ByteBuffer? = null
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
    private val visionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    // V53: Hardware-level locks to prevent HyperOS CPU/Network suspension
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // V51: Vision Watchdog to prevent LOOP_FROZEN from coroutine death
    private var visionStartTimeMs = 0L
    private val visionWatchdogJob = kotlinx.coroutines.Job()

    private val captureFrameIntervalBase = 33L
    private val captureFrameIntervalMs: Long
        get() = com.assistant.MemoryCaptureGateEngine.recommendedIntervalMs()

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
        captureState = finalState
    }

    private var currentWidth = 0
    private var currentHeight = 0
    private var currentDpi = 0

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null } ?: return@OnImageAvailableListener
        val captureNow = System.currentTimeMillis()
        if (captureNow - lastFrameProcessedMs < captureFrameIntervalMs) {
            image.close()
            return@OnImageAvailableListener
        }
        lastFrameProcessedMs = captureNow
        captureFrameCount++

        com.assistant.SplendorCaptureRecovery.markFrame()

        if (com.assistant.vision.ForegroundGate.shouldSkipCapture()) {
            image.close()
            return@OnImageAvailableListener
        }

        // OMEGA FIX: SYNCHRONOUS EXTRACTION prevents "Image is already closed" race conditions
        val width = try { image.width } catch (_: Throwable) { try { image.close() } catch (_: Throwable) {}; return@OnImageAvailableListener }
        val height = try { image.height } catch (_: Throwable) { try { image.close() } catch (_: Throwable) {}; return@OnImageAvailableListener }
        val plane = try { image.planes[0] } catch (_: Throwable) { try { image.close() } catch (_: Throwable) {}; return@OnImageAvailableListener }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val originalBuffer = plane.buffer
        
        // SPLENDOR_V23B_VISION_BUFFER_REUSE_BEGIN
        val startVision = visionInFlight.compareAndSet(false, true)

            if (startVision) {
                visionStartTimeMs = System.currentTimeMillis()
                visionScope.launch {
                    kotlinx.coroutines.delay(500)
                    if (visionInFlight.get() && System.currentTimeMillis() - visionStartTimeMs > 500L) {
                        visionInFlight.set(false)
                        try { com.assistant.diagnostic.RuntimeLogger.log("VISION_WATCHDOG_RESET: Coroutine death detected, force-unblocking frame pump.", "FAULT") } catch (_: Throwable) {}
                    }
                }
            }

        val visionBuffer: java.nio.ByteBuffer = if (startVision) {
            val required = originalBuffer.remaining()
            val existing = reusableVisionBuffer
            val buffer: java.nio.ByteBuffer = if (existing == null || existing.capacity() < required) {
                val allocated = java.nio.ByteBuffer.allocateDirect(if (required > 0) required else 1)
                reusableVisionBuffer = allocated
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
            try {
                val normalized = com.assistant.FrameNormalizer.normalize(visionBuffer, width, height, rowStride, pixelStride)
                // MUTATION TOOL 2: Compute optical variance for 0.1ms pixel-delta quantization
                com.assistant.NativeBridge.nativeComputeOpticalVariance(visionBuffer, previousVisionBuffer ?: visionBuffer, width, height, rowStride, 0, 0, width, height)
            previousVisionBuffer = visionBuffer
                val state = com.assistant.VisionCore.process(normalized)
                com.assistant.BoosterIgnition.ensureIgnited(this@OverlayService)
                com.assistant.AppContributorRegistration.ensureRegistered()
                // MUTATION TOOL 1: Escalate thread priority to MAX (-19) for zero-delay execution
                com.assistant.NativeBridge.nativeEscalateThreadPriority()
                com.assistant.RuntimeCoordinator.reportCaptureReady()
                val frame = com.assistant.FrameAssembler.assemble()
                com.assistant.RuntimeDecisionLoop.onFrame(frame)
                // WIRE SILOED GK ENGINES: Activate Dive, Claim, Rush, and Block action engines
                com.assistant.GameStateBuilder.update(state)
                com.assistant.overlay.interceptor.OmnipotentGoalkeeperEngine.scanFrameForOpponentAnimation(visionBuffer, width, height, rowStride)
                com.assistant.ControlMappingTrainer.observe(visionBuffer, width, height, rowStride)
            } catch (t: Throwable) {
                val now = System.currentTimeMillis()
                if (now - lastCaptureFaultLog > 1000L) {
                    lastCaptureFaultLog = now
                    try { RuntimeLogger.log("CAPTURE FAULT " + t.javaClass.simpleName + ": " + t.message, "FAULT") } catch (_: Throwable) {}
                }
            } finally {
                visionInFlight.set(false)
            }
        }

        // Trigger OCR if interval met (uses already-populated reusableBitmap)
        val shedFactor = when (com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.currentLoadShed()) {
            "HEAVY" -> 4L
            "LIGHT" -> 2L
            else -> 1L
        }

        if (ocrReady && System.currentTimeMillis() - lastOcrTime >= OCR_INTERVAL_MS * shedFactor) {
            lastOcrTime = System.currentTimeMillis()
            processBitmapForOCR()
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
        } catch (_: Throwable) {}

        RuntimeLogger.log("OverlayService started", "OVERLAY")
        com.assistant.vision.ForegroundGate.install(application)

        try { com.assistant.RuntimeSelfHealEngine.init(applicationContext)
              com.assistant.RuntimeSelfHealEngine.start() } catch (_: Throwable) {}
        try { com.assistant.CaptaincySkillEngine.init(applicationContext) } catch (_: Throwable) {}
        try { com.assistant.CrowdingZoneDetector.init(applicationContext) } catch (_: Throwable) {}

        instance = this
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        initializePerformanceMode()
        MmapStateEngine.initialize(cacheDir)
        ChoreographerRenderLoop.start()
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
            try {
                setupMediaProjection(resultCode, data)
                startForegroundSafely()
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
            .setContentText(com.assistant.SplendorCaptureRecovery.statusText("Engine Active"))
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
        updateOverlayVisuals("GUARD LOCK: SECURE [ANTI-BAN ON]", Color.GREEN)
        startTrajectoryWatchdog()
    }

    private fun updateOverlayVisuals(text: String, color: Int) {
        // Prevent app-painted HUD pixels from contaminating MediaProjection vision buffer
        if (captureState == CaptureState.ACTIVE || captureState == CaptureState.AUTHORIZED) return
        
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
            RuntimeLogger.log("Requested fresh MediaProjection authorization from MainActivity", "AGENT")
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
        // Clear transient panic state on fresh capture start to prevent stale contamination
        SmartAssistRepository.clearPanic()
        
        // Guarantee zero app-painted pixels enter MediaProjection capture surface
        try { txtEngineStatus.visibility = View.GONE } catch (_: Throwable) {}
        try { panicIndicator?.visibility = View.GONE } catch (_: Throwable) {}
        
        if (captureState == CaptureState.ACTIVE || captureState == CaptureState.AUTHORIZED) {
            teardownCaptureResourcesInternal()
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = projectionManager.getMediaProjection(code, intent)

        if (mp == null) {
            captureState = CaptureState.FAILED
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

    private fun recreateCaptureSurfaces() {
        captureLock.lock()
        try {
            recreateCaptureSurfacesInternal()
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

        val isInitial = virtualDisplay == null
        val dimensionsChanged = finalWidth != currentWidth || finalHeight != currentHeight || metrics.densityDpi != currentDpi

        if (isInitial) {
            val reader = ImageReader.newInstance(finalWidth, finalHeight, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener(imageAvailableListener, ocrIoHandler ?: Handler(Looper.getMainLooper()))
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
            
            // V38 LOOP_FROZEN FIX: Force-kill stuck vision coroutines and unblock visionInFlight
            visionScope.coroutineContext.cancelChildren()
            visionInFlight.set(false)
            
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
                            recreateCaptureSurfaces()
                            lastFrameProcessedMs = System.currentTimeMillis()
                        } catch (e: Exception) {
                            RuntimeLogger.log("KEEPALIVE replace failed: ${e.message}", "OVERLAY")
                        }
                    }
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
                recognizer.process(InputImage.fromBitmap(reusableBitmap!!, 0))
                    .addOnSuccessListener { visionText ->
                        val detectedText = visionText.textBlocks.asSequence()
                            .filterNot { com.assistant.vision.OverlaySelfMask.isSelfDrawnCapture(it.boundingBox) }
                            .joinToString("") { it.text }
                            .replace("\n", "")
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
            }
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

    private fun startTrajectoryWatchdog() {
        trajectoryRunnable = object : Runnable {
            override fun run() {
                if (!::overlayView.isInitialized) return
                val panicActive = SmartAssistRepository.panicActive()
                if (!panicActive && SmartAssistRepository.panicActive()) {
                    // PHASE10_PANIC_PERSISTENCE_KEEP_STATE
                }
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
        SmartAssistRepository.clearPanic()
        com.assistant.vision.OverlaySelfMask.clearPrefix("hud")
        com.assistant.RuntimeCoordinator.shutdown()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { perfHintSession?.close() } catch (_: Throwable) {}
        }
        perfHintSession = null
        ChoreographerRenderLoop.stop()
        MmapStateEngine.release()
        ocrIoThread?.quitSafely()
        ocrIoThread = null

        
        // V53: Release hardware locks
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {}

        super.onDestroy()
    }
}


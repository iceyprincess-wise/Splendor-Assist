package com.assistant
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.RuntimeMetricsRegistry
import com.assistant.adapter.smartassist.SmartAssistRepository
import com.assistant.overlay.database.MatchAnalyticsEntity
import com.assistant.survival.OverlaySurvivalEngine
import com.assistant.overlay.database.TheaterDatabase
import com.assistant.overlay.metrics.SmartAssistMetrics
import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import com.assistant.overlay.notification.RuntimeNotificationCoordinator
import com.assistant.overlay.dvr.DvrRuntimeCoordinator
import com.assistant.overlay.dvr.DvrSessionCoordinator
import com.assistant.overlay.dvr.MatchSessionEngine
import com.assistant.overlay.runtime.PerformanceGovernor
import com.assistant.overlay.analytics.LiveMatchAnalytics
import com.assistant.overlay.storage.MediaStoreStorageEngine
import java.util.UUID
import java.util.concurrent.Executors

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
import android.media.MediaRecorder
import android.view.Surface
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

    @Volatile
    private var recorderInitialized = false


    companion object {
        private const val CHANNEL_ID = "efootball_assistant_channel"
        private const val NOTIFICATION_ID = 101
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

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null

    private var activeAnalyticsMatchId:String?=null
    private var activeRecordingStart:Long=0L
    private var recorderVirtualDisplay: VirtualDisplay? = null


    private var perfHintSession: PerformanceHintManager.Session? = null
    private var ocrIoThread: android.os.HandlerThread? = null
    private var ocrIoHandler: android.os.Handler? = null
    private var lastOcrTime = 0L
    private var lastMatchDetectionTime = 0L
    private val OCR_INTERVAL_MS = 1500L 
    private var reusableBitmap: Bitmap? = null
    private val taskExecutionLock = ReentrantLock()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val analyticsExecutor =
        Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    
override fun onCreate() {

        if(runtimeInitialized){
            return
        }

        runtimeInitialized=true

        super.onCreate()
        RuntimeLogger.log("OverlayService started", "OVERLAY")
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
                val hintManager = getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
                perfHintSession = hintManager?.createHintSession(intArrayOf(Process.myTid()), 8333333L)
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

            // [CRASH FIX] DVR foreground-service start disabled:
            // it went foreground as mediaProjection before consent was validated,
            // causing SecurityException on Android 14+ (SDK 34+). Safe to skip.
            // startService(
            //     android.content.Intent(
            //         this,
            //         com.assistant.overlay.dvr.DvrProjectionService::class.java
            //     )
            // )
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
            val logFile = File(getExternalFilesDir(null), "crash_log.txt")
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
        OverlaySurvivalEngine.attached()
        updateOverlayVisuals("GUARD LOCK: SECURE [ANTI-BAN ON]", Color.GREEN)
        startTrajectoryWatchdog(
            overlayView,
            Handler(Looper.getMainLooper())
        )
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

    private fun setupMediaProjection(code: Int, intent: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(code, intent)
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Handler(Looper.getMainLooper()).post {
                                        stopSelf()
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
        imageReader = ImageReader.newInstance(finalWidth, finalHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
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

                com.assistant.overlay.interceptor.OmnipotentGoalkeeperEngine.scanFrameForOpponentAnimation(scanBuffer, image.width, image.height)
            } catch (_: Exception) {}
            if (System.currentTimeMillis() - lastOcrTime >= OCR_INTERVAL_MS) {
                lastOcrTime = System.currentTimeMillis()
                processImageForOCR(image)
            } else {
                image.close()
            }
        }, ocrIoHandler ?: Handler(Looper.getMainLooper()))
        virtualDisplay = mediaProjection?.createVirtualDisplay("HybridCoachScreen", finalWidth, finalHeight, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, imageReader?.surface, null, null)
    }

    private fun processImageForOCR(image: Image) {
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
                } catch (_: Exception) {}

                recognizer.process(InputImage.fromBitmap(reusableBitmap!!, 0))
                    .addOnSuccessListener { visionText ->

                        val detectedText =
                            visionText.text
                                .replace("\n", " ")
                                .take(120)

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
                            !detectedText.contains("🫆", true) &&
                            !detectedText.contains("🫆", true) &&
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
                            if (lv.hasRealData) {
                                val pipe = com.assistant.adapter.smartassist.SmartAssistPipeline()
                                val dec = pipe.computeOptimalVector(lv.startX, lv.startY, lv.endX, lv.endY, lv.duration)
                                if (dec.shouldAct) {
                                    com.assistant.execution.CentralExecutionBus.submit(pipe.createExecutionRequest(dec))
                                }
                            }

                            RuntimeMetricsRegistry
                                .matchDetections
                                .incrementAndGet()

                            MatchSessionEngine.onGameplayFrame()

                            DvrSessionCoordinator.beginSession()

                            val recording =
                                DvrRuntimeCoordinator.recording()

                            val recordingAllowed =
                                PerformanceGovernor.allowRecording(
                                    applicationContext,
                                    thermalLevel = 0
                                )

                            RuntimeNotificationCoordinator.update(
                                context = applicationContext,
                                antiban = true,
                                matchDetected = true,
                                recording =
                                    recording && recordingAllowed,
                                saved = false
                            )

                            RuntimeLogger.log(
                                "🫆",
                                "SMART_ASSIST"
                            )

                            analyticsExecutor.execute {

                                try {

                                    val matchId =
                                        UUID.randomUUID().toString()

                                    val now =
                                        System.currentTimeMillis()

                                    activeAnalyticsMatchId=matchId
                                    activeRecordingStart=now

                                    val analytics =
                                        MatchAnalyticsEntity(
                                            matchId = matchId,
                                            startTimestamp = now,
                                            endTimestamp = now,
                                            dvrVideoPath = "",
                                            isPermanentlySaved = false,
                                            possessionPercentage =
                                                LiveMatchAnalytics.possession(),

                                            longPassEfficiency =
                                                LiveMatchAnalytics.passing(),

                                            defensiveInterceptions =
                                                LiveMatchAnalytics.interceptions(),

                                            transitionSpeedMs =
                                                LiveMatchAnalytics.transition(),
                                            errorTimelineJson = "[]"
                                        )

                                    TheaterDatabase
                                        .getDatabase(applicationContext)
                                        .theaterDao()
                                        .insertMatchData(
                                            analytics
                                        )

                                    RuntimeMetricsRegistry
                                        .analyticsProduced
                                        .incrementAndGet()

                                    RuntimeLogger.log(
                                        "Analytics produced: $matchId",
                                        "ANALYTICS"
                                    )

                                } catch (e: Exception) {

                                    RuntimeLogger.log(
                                        "Analytics producer failed",
                                        "ANALYTICS"
                                    )
                                }
                            }

                            updateOverlayVisuals(
                                "🫆",
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

                    MatchSessionEngine.heartbeat()

                    Thread.sleep(50)

                } catch (e: InterruptedException) {

                    break
                }
            }
        }.apply { start() }
    }

    
    
private fun startRuntimeRecorder() {

        if (!DvrSessionCoordinator.active())
            return

        if (mediaRecorder != null)
            return

        if (recorderInitialized) {
            return
        }

        recorderInitialized = true

        RuntimeLogger.log(
            "Runtime recorder armed",
            "DVR"
        )

        val dir =
            File(cacheDir,"runtime_recordings")

        dir.mkdirs()

        recordingFile =
            File(
                dir,
                "match_" +
                System.currentTimeMillis() +
                ".mp4"
            )

        mediaRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {

                setVideoSource(
                    MediaRecorder.VideoSource.SURFACE
                )

                setOutputFormat(
                    MediaRecorder.OutputFormat.MPEG_4
                )

                setVideoEncoder(
                    MediaRecorder.VideoEncoder.H264
                )

                setVideoFrameRate(30)

                setVideoEncodingBitRate(
                    4_000_000
                )

                setVideoSize(
                    imageReader!!.width,
                    imageReader!!.height
                )

                setOutputFile(
                    recordingFile!!.absolutePath
                )

                prepare()
            }

        recorderVirtualDisplay =
            mediaProjection?.createVirtualDisplay(

                "SplendorRecorder",

                imageReader!!.width,

                imageReader!!.height,

                resources.displayMetrics.densityDpi,

                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,

                mediaRecorder!!.surface,

                null,

                null

            )

        mediaRecorder!!.start()
    }

    
private fun stopRuntimeRecorder() {

        recorderInitialized=false


        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }

        try {
            recorderVirtualDisplay?.release()
        } catch (_: Exception) {
        }

        recorderVirtualDisplay = null
        val completedRecording =
            recordingFile

        mediaRecorder = null
        recordingFile = null

        completedRecording?.let {

            try {

                MediaStoreStorageEngine.saveToRom(
                    context = applicationContext,
                    matchId =
                        System.currentTimeMillis()
                            .toString(),
                    sourcePath =
                        it.absolutePath
                ){

                    try{

                        activeAnalyticsMatchId?.let{ id->

                            TheaterDatabase
                                .getDatabase(applicationContext)
                                .theaterDao()
                                .insertMatchData(

                                    MatchAnalyticsEntity(

                                        matchId=id,

                                        startTimestamp=
                                            activeRecordingStart,

                                        endTimestamp=
                                            System.currentTimeMillis(),

                                        dvrVideoPath=
                                            completedRecording.absolutePath,

                                        isPermanentlySaved=true,

                                        possessionPercentage=
                                            LiveMatchAnalytics.possession(),

                                        longPassEfficiency=
                                            LiveMatchAnalytics.passing(),

                                        defensiveInterceptions=
                                            LiveMatchAnalytics.interceptions(),

                                        transitionSpeedMs=
                                            LiveMatchAnalytics.transition(),

                                        errorTimelineJson="[]"
                                    )
                                )
                        }

                    }catch(_:Exception){}

                    RuntimeNotificationCoordinator.update(
                        context = applicationContext,
                        antiban = true,
                        matchDetected = false,
                        recording = false,
                        saved = true
                    )

                    DvrSessionCoordinator.completeSave()

                    RuntimeLogger.log(
                        "Recording exported",
                        "DVR"
                    )

                }

            } catch(e:Exception){

                RuntimeLogger.log(
                    "Export failed",
                    "DVR"
                )
            }

        }

        DvrSessionCoordinator.finishSession()

        RuntimeNotificationCoordinator.update(
            context = applicationContext,
            antiban = true,
            matchDetected = false,
            recording = false,
            saved = true
        )

        RuntimeLogger.log(
            "Runtime recorder stopped",
            "DVR"
        )
    }

override fun onDestroy() {
        OverlaySurvivalEngine.destroyed()
        isRunning = false
        // PHASE10_PANIC_PERSISTENCE_KEEP_STATE
        try { windowManager.removeViewImmediate(overlayView) } catch (_: Exception) {}
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { projectionCallback?.let { mediaProjection?.unregisterCallback(it) } } catch (_: Exception) {}
        stopRuntimeRecorder()

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

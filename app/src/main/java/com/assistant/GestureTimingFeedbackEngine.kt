package com.assistant

import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.Choreographer
import com.assistant.VisionTrust
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import com.assistant.survival.ResourceBudgetRegistry
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object GestureTimingFeedbackEngine {

    // 120ms is ~2 frames at 15fps. Anything older is unusable in fast-paced eFootball.
    private const val TIMEOUT_MS = 120L   
    private const val EWMA_ALPHA = 0.4f
    private const val LOG_EVERY_N = 50

    @Volatile var avgDispatchMs = 0f; private set
    @Volatile var expiredCount = 0L; private set
    @Volatile var measuredCount = 0L; private set
    @Volatile var lastDispatchMs = 0L; private set

    private val pendingSubmitMs = AtomicLong(-1L)
    private val pendingSeq = AtomicLong(-1L)
    private val globalSeq = AtomicLong(0L)

    /** Called at CentralExecutionBus.submit() time for SMART_ASSIST gestures. */
    fun recordSubmission(): Long {
        val seq = globalSeq.incrementAndGet()
        pendingSeq.set(seq)
        pendingSubmitMs.set(System.currentTimeMillis())
        return seq
    }

    /**
     * Called at gesture execution start in SmartAssistAccessibilityEngine.
     * seq must match the pending sequence to count.
     */
    fun recordDispatch(seq: Long) {
        val submitMs = pendingSubmitMs.get()
        if (submitMs < 0 || pendingSeq.get() != seq) return
        val elapsed = System.currentTimeMillis() - submitMs
        pendingSubmitMs.set(-1L)
        val clampedMs = elapsed.coerceIn(0L, 5000L).toFloat()
        avgDispatchMs = if (avgDispatchMs == 0f) clampedMs
                        else avgDispatchMs * (1 - EWMA_ALPHA) + clampedMs * EWMA_ALPHA
        lastDispatchMs = elapsed
        measuredCount++

        // CONDITIONLESS ACTIVE MITIGATION:
        // If a single gesture takes longer than 1 frame at 15fps (66ms),
        // immediately boost the current thread priority to prevent starvation
        // of subsequent gestures in the fast-paced eFootball environment.
        if (elapsed > 66L) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}
        }

        val cls = when {
            avgDispatchMs < 33f  -> "GESTURE_FAST"
            avgDispatchMs < 66f  -> "GESTURE_OK"
            avgDispatchMs < 120f -> "GESTURE_SLOW"
            else                 -> "GESTURE_LAGGING"
        }
        
        // Publish emergency signal if average latency exceeds 2 frames
        val signal = if (avgDispatchMs >= 120f) "GESTURE_EMERGENCY" else cls
        AdapterSignalBus.publishInput(signal, avgDispatchMs.toLong())

        if (measuredCount % LOG_EVERY_N == 0L) {
            RuntimeLogger.log(
                "GestureDispatch avg=${String.format("%.1f", avgDispatchMs)}ms " +
                    "last=${elapsed}ms expired=$expiredCount measured=$measuredCount",
                "INPUT"
            )
        }
    }

    /**
     * Called periodically to detect gestures that were submitted but
     * never dispatched (bus staleness / queue expiry).
     */
    fun checkExpiry() {
        val submitMs = pendingSubmitMs.get()
        if (submitMs < 0) return
        if (System.currentTimeMillis() - submitMs > TIMEOUT_MS) {
            expiredCount++
            pendingSubmitMs.set(-1L)
            
            // HARDWORKING ELIMINATOR: Force boost main thread when queue is stale
            try {
                Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}
            
            // Signal the CentralExecutionBus to drop stale events
            AdapterSignalBus.publishInput("BUS_STALE_PURGE", expiredCount)
            
            RuntimeLogger.log(
                "GestureDispatch EXPIRED after ${TIMEOUT_MS}ms — bus queue pressure " +
                    "(total expired=$expiredCount). PURGE & BOOST triggered.",
                "INPUT"
            )
        }
    }

    fun reset() {
        avgDispatchMs = 0f; expiredCount = 0L; measuredCount = 0L
        lastDispatchMs = 0L; pendingSubmitMs.set(-1L); pendingSeq.set(-1L)
    }
}


/* ========
InputLatencyEngine
======== */

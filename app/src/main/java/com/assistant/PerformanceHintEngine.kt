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

object PerformanceHintEngine {
    @Volatile private var captureActive = false
    private var session: PerformanceHintManager.Session? = null
    private var sessionThreadId = -1
    private var sessionTargetIntervalNanos = -1L
    private val lock = Any()

    /** Called for each new capture lifetime; a session is created lazily on the vision worker. */
    fun beginCapture() = synchronized(lock) {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        sessionThreadId = -1
        sessionTargetIntervalNanos = -1L
        captureActive = true
    }

    fun endCapture() = synchronized(lock) {
        captureActive = false
        try { session?.close() } catch (_: Throwable) {}
        session = null
        sessionThreadId = -1
        sessionTargetIntervalNanos = -1L
    }

    fun reportActualWorkload(context: Context, actualDurationNanos: Long, targetFps: Int) {
        if (!captureActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val targetIntervalNanos = 1_000_000_000L / targetFps.coerceIn(30, 60)
        synchronized(lock) {
            if (!captureActive) return
            val tid = Process.myTid()
            if (session == null || sessionThreadId != tid || sessionTargetIntervalNanos != targetIntervalNanos) {
                try { session?.close() } catch (_: Throwable) {}
                val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return
                session = try { manager.createHintSession(intArrayOf(tid), targetIntervalNanos) } catch (_: Throwable) { null }
                sessionThreadId = if (session == null) -1 else tid
                sessionTargetIntervalNanos = if (session == null) -1L else targetIntervalNanos
            }
            try { session?.reportActualWorkDuration(actualDurationNanos.coerceAtLeast(1L)) } catch (_: Throwable) {
                try { session?.close() } catch (_: Throwable) {}
                session = null
                sessionThreadId = -1
                sessionTargetIntervalNanos = -1L
            }
        }
    }
}

/* ========
RehydratedStateSnapshot
======== */

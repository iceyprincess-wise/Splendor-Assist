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

object RenderThreadStallEngine {
    @Volatile private var running = false
    @Volatile var gpuQueueStallsPerMin = 0f; private set
    @Volatile private var winStalls = 0L
    @Volatile private var lastFrameEndNanos = 0L
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameEndNanos > 0L) {
                val gapMs = (frameTimeNanos - lastFrameEndNanos) / 1_000_000f
                val vsync = DisplayProfileEngine.vsyncBudgetMs
                if (gapMs > (vsync * 2.5f)) winStalls++
            }
            lastFrameEndNanos = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    fun start() {
        if (running) return; running = true
        Handler(Looper.getMainLooper()).post { Choreographer.getInstance().postFrameCallback(callback) }
        val t = Thread {
            while (running) {
                try { Thread.sleep(10_000L) } catch (_: Throwable) { return@Thread }
                val winMin = 10_000L / 60000f
                gpuQueueStallsPerMin = winStalls / winMin
                if (winStalls > 0) RuntimeLogger.log("RenderThread/GPU stalls/min=" + String.format("%.1f", gpuQueueStallsPerMin), "LAGRENDER")
                winStalls = 0L
            }
        }
        t.isDaemon = true; t.name = "lag-render-stall"; t.start()
    }
    fun stop() {
        running = false
        Handler(Looper.getMainLooper()).post { Choreographer.getInstance().removeFrameCallback(callback) }
    }
}

// --- NEW: NetJitterEngine ---

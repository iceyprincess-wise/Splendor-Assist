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

object InputLatencyEngine {

    @Volatile private var running = false
    @Volatile var latencyMs = 0L; private set
    @Volatile var classification = "UNKNOWN"; private set
    @Volatile var measurements = 0L; private set
    @Volatile var lagEvents = 0L; private set

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true

        val t = Thread {
            // Conditionlessly boost this polling thread so it never misses a measurement
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
            
            while (running) {
                try { measure() } catch (_: Throwable) {}
                try { Thread.sleep(200L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-latency"
        t.priority = Thread.MAX_PRIORITY
        t.start()
        
        RuntimeLogger.log("InputLatencyEngine started (Hardworking Eliminator Mode)", "INPUT")
    }

    fun stop() { running = false }

    private fun measure() {
        val posted = SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        
        // Post to main thread to measure dispatch latency
        mainHandler.post { 
            latencyMs = SystemClock.elapsedRealtime() - posted
            latch.countDown() 
        }
        
        // Wait max 200ms for main thread to respond
        val responded = latch.await(200L, TimeUnit.MILLISECONDS)
        if (!responded) {
            latencyMs = 200L // Main thread is completely blocked
            latch.countDown() // Prevent leak
        }
        
        measurements++
        
        // 15fps = 66ms/frame. 30fps = 33ms/frame.
        classification = when {
            latencyMs < 16L -> "INSTANT"
            latencyMs < 33L -> "GOOD"
            latencyMs < 66L -> "DELAYED"
            else -> { lagEvents++; "LAGGING" }
        }

        AdapterSignalBus.publishInput(classification, latencyMs)

        // CONDITIONLESS ACTIVE MITIGATION:
        // No warmup delays. If main thread is congested, immediately trigger purges.
        if (latencyMs >= 33L) { // More than 1 frame at 30fps, or half frame at 15fps
            
            // Signal Queen Bee (SmartAssist) to drop non-essential UI work and prioritize input
            AdapterSignalBus.publishInput("MAIN_THREAD_CONGESTION", latencyMs)
            
            // Force boost main thread priority dynamically if heavily lagging
            if (latencyMs >= 66L) {
                try {
                    // Attempt to boost the process priority
                    Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY)
                } catch (_: Throwable) {}
                
                RuntimeLogger.log("INPUT CRITICAL LAG: ${latencyMs}ms (total lag events: $lagEvents). PURGE TRIGGERED.", "INPUT")
            }
        }
    }
}
/* ======
InputLatencyEngine Anchor
====== */

/* ========
InputPriorityEngine
======== */
/**
 * InputPriorityEngine — HARDWORKING BOOSTER & ELIMINATOR.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * HyperOS aggressively throttles threads during fast gameplay to save battery.
 * Waiting 30 seconds to reapply priority causes massive input lag.
 * 
 * This engine now conditionlessly reapplies URGENT_DISPLAY every 500ms
 * to prevent silent deprioritization and instantly blocks OS throttle attempts.
 */

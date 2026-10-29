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

object LoadShedGovernor {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val MIN_HOLD_MS: Long get() = 8000L
    private val POLL_MS: Long get() = 2000L
    private val ARM_POLLS: Int get() = 4
    private val RELEASE_POLLS: Int get() = 5

    @Volatile private var running = false
    @Volatile var level = "NONE"; private set
    @Volatile private var candidate = "NONE"
    @Volatile private var streak = 0
    @Volatile private var lastChangeMs = 0L
    @Volatile private var startTimeMs = 0L

    fun start() {
        if (running) return
        running = true
        startTimeMs = System.currentTimeMillis()
        val t = Thread {
            while (running) {
                try {
                    // FAST PATH: a SEIZURE burst escalates immediately -
                    // sub-second truth beats the report window when a freeze hits
                    val burst = PerformanceTelemetryRegistry.currentStutterState()
                    val bootAge=System.currentTimeMillis()-startTimeMs
                    if(startTimeMs>0L&&bootAge<10_000L){try{Thread.sleep(POLL_MS.coerceAtLeast(1L))}catch(_:Throwable){return@Thread};continue}
                    val want = when {
                        burst == "SEIZURE" -> "HEAVY"
                        LagVerdictEngine.verdict == "CHOKING" -> "HEAVY"
                        LagVerdictEngine.verdict == "JITTERY" -> "LIGHT"
                        else -> "NONE"
                    }
                    if (want == candidate) streak++ else { candidate = want; streak = 1 }
                    val arm = if (ARM_POLLS < 1) 1 else ARM_POLLS
                    val rel = if (RELEASE_POLLS < 1) 1 else RELEASE_POLLS
                    val need = if (candidate == "NONE") rel else arm
                    val now = System.currentTimeMillis()
                    if (candidate != level && streak >= need &&
                        (lastChangeMs == 0L || now - lastChangeMs >= MIN_HOLD_MS)) {
                        RuntimeLogger.log("LOAD SHED " + level + " -> " + candidate +
                            " (device=" + LagVerdictEngine.verdict + ")", "LOADSHED")
                        level = candidate
                        lastChangeMs = now
                    }
                    PerformanceTelemetryRegistry.publishLoadShed(level)
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-loadshed"; t.start()
    }

    fun stop() { running = false }
}

// --- MERGED: MainThreadStallEngine.kt ---

// V3 ADMIN-WIRED - zero main-thread I/O, every knob answers the admin store live

/**
 * Pokes the main thread on a fixed rhythm and measures how late the answer
 * comes back - lateness IS the choke other engines cannot see. The probe
 * only measures (no logging/IO on the thread under test); a background
 * reporter summarizes. V3: cadence, spike line, smoothing and report rhythm
 * are all admin-tunable and re-read every cycle - values apply on the very
 * next poke, no restart.
 */

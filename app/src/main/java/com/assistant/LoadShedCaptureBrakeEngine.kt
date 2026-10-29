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

object LoadShedCaptureBrakeEngine {

    private const val POLL_MS = 500L

    @Volatile private var running = false
    @Volatile var executionBrake = 0; private set
    @Volatile private var lastLevel = "NONE"

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try { poll() } catch (_: Throwable) {}
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "lag-capture-brake"
        t.start()
        RuntimeLogger.log("LoadShedCaptureBrakeEngine started", "LOADSHED")
    }

    fun stop() { running = false }

    private fun poll() {
        val level = LoadShedGovernor.level
        val brake = when (level) {
            "HEAVY" -> 2
            "LIGHT" -> 1
            else    -> 0
        }
        val changed = brake != executionBrake || level != lastLevel
        executionBrake = brake
        lastLevel = level

        AdapterSignalBus.publishExecutionBrake(brake)

        if (changed) {
            RuntimeLogger.log(
                "LoadShedBrake: loadShed=$level → executionBrake=$brake",
                "LOADSHED"
            )
        }
    }

    /** Recommended capture interval Ms for current brake level. */
    fun recommendedIntervalMs(): Long = when (executionBrake) {
        2    -> 100L  // HEAVY: 10fps
        1    -> 66L   // LIGHT: 15fps
        else -> 0L    // NONE: use base rate (0 = no override)
    }

    /**
     * Returns true when HEAVY load shed is active and MOVE-class gesture
     * submissions should be suppressed (action-class gestures still pass).
     */
    fun suppressMoveGestures(): Boolean = executionBrake >= 2
}

// --- MERGED: LoadShedGovernor.kt ---

// V3 ADMIN-WIRED - quick to arm, slow to thrash, every knob live

/**
 * The rescue: when the judge says the device is struggling, this raises the
 * shed level (NONE / LIGHT / HEAVY) that the rest of the runtime obeys to
 * drop non-essential work. A SEIZURE stutter burst escalates IMMEDIATELY
 * (fast path, no waiting). V3: poll rhythm, arm/release confirm counts and
 * the minimum hold are all admin-tunable, re-read every poll.
 */

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

object PanelWatchEngine {

    // ADMIN-TUNABLE
    private val POLL_MS: Long get() = 5000L

    @Volatile private var running = false
    private var dm: DisplayManager? = null
    private var listener: DisplayManager.DisplayListener? = null

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        try {
            val mgr = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm = mgr
            val l = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) { }
                override fun onDisplayRemoved(displayId: Int) { }
                override fun onDisplayChanged(displayId: Int) { apply() }
            }
            mgr.registerDisplayListener(l, Handler(Looper.getMainLooper()))
            listener = l
            RuntimeLogger.log("instant panel-change listener registered", "STUTTER")
        } catch (t: Throwable) {
            RuntimeLogger.log("panel listener unavailable, sweep only: " + t.message, "STUTTER")
        }
        val t = Thread {
            while (running) {
                try { apply() } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "stutter-panelwatch"; t.start()
    }

    private fun apply() {
        val hz = dm?.displays?.firstOrNull()?.refreshRate ?: return
        StutterPulseEngine.onPanelRate(hz)
    }

    fun stop() {
        running = false
        try { listener?.let { dm?.unregisterDisplayListener(it) } } catch (_: Throwable) { }
        listener = null
    }
}
/* ======
PanelWatchEngine Anchor
====== */


/* ========
StutterPulseEngine
======== */
// V3 ADMIN-WIRED - every knob answers the admin store live, publishes for the Detector

/**
 * Sub-second burst radar. Lag grades long windows; this watches EVERY slice
 * (default 1s). A burst = enough frames in one slice blowing past the burst
 * line (screen beat x multiplier, beat read from the REAL panel and updated
 * live by PanelWatchEngine). Catches the 3-5 frame hiccups an average washes
 * out - the felt micro-stutter.
 *
 * V3: burst line, frames-per-slice, slice length and readout rhythm are all
 * admin-tunable and re-read every frame - values apply instantly, no
 * restart. Publishes live burst truth for the admin Detector.
 */

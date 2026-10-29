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

object NetworkStateEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
    private val POLL_MS: Long get() = 10000L

    @Volatile var transport: String = "NONE"
        private set
    @Volatile private var running = false
    @Volatile private var last = ""
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        try {
            val mgr = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm = mgr
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    apply(app, classify(caps))
                }
                override fun onLost(network: Network) { apply(app, "NONE") }
            }
            mgr.registerDefaultNetworkCallback(cb)
            callback = cb
            RuntimeLogger.log("instant network-change callback registered", "NET")
        } catch (t: Throwable) {
            RuntimeLogger.log("network callback unavailable, poll only: " + t.message, "NET")
        }
        val th = Thread {
            while (running) {
                try {
                    val mgr = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    apply(app, classify(mgr.getNetworkCapabilities(mgr.activeNetwork)))
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        th.isDaemon = true; th.name = "net-state"; th.start()
    }

    private fun classify(caps: NetworkCapabilities?): String = when {
        caps == null -> "NONE"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        else -> "OTHER"
    }

    @Synchronized
    private fun apply(app: Context, now: String) {
        transport = now
        if (now == last) return
        last = now
        if (now == "WIFI") CarrierProfileEngine.useWifiProfile()
        else CarrierProfileEngine.detect(app)
        NetProbeEngine.onLinkChanged()
        DnsWarmupEngine.warmNow()
        RuntimeLogger.log("Transport -> " + now + " (stack re-learning link now)", "NET")
    }

    fun stop() {
        running = false
        try { callback?.let { cm?.unregisterNetworkCallback(it) } } catch (_: Throwable) { }
        callback = null
    }
}
/* ======
NetworkStateEngine Anchor
====== */

/* ========
PacketLossProbeEngine
======== */
// V3 INSTANT-REFLEX

/**
 * Measures PACKET LOSS - the metric TCP probes cannot see. Sends real UDP DNS
 * queries and counts replies. V3: queries alternate between two independent
 * resolvers (8.8.8.8 / 1.1.1.1) so a single resolver hiccup no longer reads
 * as link loss; the gap between queries is admin-tunable; live loss is
 * published for the admin Detector.
 */

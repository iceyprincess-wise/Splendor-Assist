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

object ConnectionHealEngine {
    @Volatile private var running = false
    @Volatile private var lastHealMs = 0L
    @Volatile var healCount = 0; private set
    private const val COOLDOWN_MS = 15_000L

    fun start(ctx: Context) {
        if (running) return
        running = true
        val appCtx = ctx.applicationContext
        try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    RuntimeLogger.log("ConnectionHeal: network LOST — healing", "NETHEAL")
                    tryHeal(appCtx)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.linkDownstreamBandwidthKbps in 1..500) {
                        RuntimeLogger.log("ConnectionHeal: bandwidth critical — healing", "NETHEAL")
                        tryHeal(appCtx)
                    }
                }
            })
        } catch (t: Throwable) {
            RuntimeLogger.log("ConnectionHeal: callback failed: ${t.message}", "NETHEAL")
        }
        val t = Thread {
            while (running) {
                try { if (ActionWindowEngine.verdict == "HOLD") tryHeal(appCtx) } catch (_: Throwable) {}
                try { Thread.sleep(8_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-heal"; t.start()
        RuntimeLogger.log("ConnectionHealEngine started", "NETHEAL")
    }

    fun stop() { running = false }

    private fun tryHeal(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastHealMs < COOLDOWN_MS) return
        lastHealMs = now; healCount++
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wm.isWifiEnabled) {
                @Suppress("DEPRECATION") wm.startScan()
                RuntimeLogger.log("ConnectionHeal: WiFi rescan #$healCount", "NETHEAL")
            }
        } catch (_: Throwable) {}
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.let { cm.bindProcessToNetwork(it) }
            RuntimeLogger.log("ConnectionHeal: rebound to best network #$healCount", "NETHEAL")
        } catch (_: Throwable) {}
    }
}

/* ========
DnsWarmupEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V3: warmNow() lets the network-state engine force an immediate re-warm the
 * second the link changes - server addresses are hot on the NEW network
 * within a second instead of waiting out the rewarm interval.
 */

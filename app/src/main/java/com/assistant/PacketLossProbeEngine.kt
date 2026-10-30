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

object PacketLossProbeEngine {

    private val RESOLVERS = listOf("8.8.8.8", "1.1.1.1")

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val ROUND_MS: Long get() = 4000L
    private val PER_ROUND: Int get() = 4
    private val REPLY_TIMEOUT_MS: Int get() = 700
    private val ALPHA: Float get() = 0.3f
    private val GAP_MS: Long get() = 80L

    @Volatile private var running = false
    @Volatile var lossPct = 0f; private set
    @Volatile private var rounds = 0L
    @Volatile private var seq = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val perRound = if (PER_ROUND < 1) 1 else PER_ROUND
                    val gap = GAP_MS
                    var ok = 0
                    repeat(perRound) {
                        if (queryOnce()) ok++
                        if (gap > 0) try { Thread.sleep(gap) } catch (_: Throwable) { }
                    }
                    val roundLoss = (perRound - ok) * 100f / perRound
                    val a = ALPHA
                    lossPct = lossPct * (1 - a) + roundLoss * a
                    rounds++
                    if (roundLoss >= 50f)
                        RuntimeLogger.log("LOSS SPIKE " + String.format("%.0f", roundLoss) +
                            "% this round (avg " + String.format("%.0f", lossPct) + "%)", "NETLOSS")
                    else if (rounds % 15L == 0L)
                        RuntimeLogger.log("loss avg=" + String.format("%.0f", lossPct) + "% rounds=" + rounds, "NETLOSS")
                } catch (_: Throwable) { }
                val nap = ROUND_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-loss"; t.start()
    }

    fun stop() { running = false }

    private fun queryOnce(): Boolean = try {
        val host = RESOLVERS[(seq++ % RESOLVERS.size).toInt()]
        val id = Random.nextInt(0x0000FFFF)
        val q = dnsQuery(id)
        DatagramSocket().use { s ->
            s.soTimeout = REPLY_TIMEOUT_MS
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(host), 53))
            val buf = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            s.receive(resp)
            resp.length >= 2 &&
                ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)) == id
        }
    } catch (_: Throwable) { false }

    private fun dnsQuery(id: Int): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(id shr 8); b.write(id and 0xFF)
        b.write(0x01); b.write(0x00)          // recursion desired
        b.write(0x00); b.write(0x01)          // 1 question
        b.write(ByteArray(6))                  // no answer/auth/extra
        for (label in listOf("www", "google", "com")) {
            b.write(label.length); b.write(label.toByteArray())
        }
        b.write(0)
        b.write(0x00); b.write(0x01)           // type A
        b.write(0x00); b.write(0x01)           // class IN
        return b.toByteArray()
    }
}

/* ========
RadioKeepAliveEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V2: cadence adapts - when the link is dirty the radio is pinned at high
 * power MORE often (every rhythm/2 s, floor = admin value), because a dirty
 * link plus a sleepy radio compounds into the worst first-touch latency.
 * V3: the base rhythm itself is override-aware (net.profile.keepalive_s),
 * so the admin controls both the rhythm and the floor.
 */

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

object CarrierProfileEngine {

    private val MTN     = CarrierProfile("MTN", 65, 8, 25)
    private val AIRTEL  = CarrierProfile("AIRTEL", 75, 10, 30)
    private val GENERIC = CarrierProfile("GENERIC", 90, 12, 40)
    val WIFI            = CarrierProfile("WIFI", 40, 15, 15)

    @Volatile var current: CarrierProfile = GENERIC
        private set

    // ---- override-aware baselines (0 = auto: use the detected profile) ----
    val baselineRttMs: Int get() {
        val o = 0
        return if (o > 0) o else current.expectedRttMs
    }
    val jitterTolMs: Int get() {
        val o = 0
        return if (o > 0) o else current.jitterToleranceMs
    }
    val keepAliveS: Int get() {
        val o = 0
        return if (o > 0) o else current.keepAliveSeconds
    }

    fun detect(ctx: Context): CarrierProfile {
        val op = try {
            // PHASE4 FIX: dual-SIM bug — networkOperatorName returns PRIMARY SIM (MTN slot 1)
            // even when active DATA SIM is different (Airtel slot 2).
            // Log-proven: user on Airtel, but carrier profile logged as MTN every session.
            // Fix: read operator from the explicit DATA subscription ID.
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val dataTm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    val subId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                    if (subId > 0) tm?.createForSubscriptionId(subId) else tm
                } catch (_: Throwable) { tm }
            } else tm
            dataTm?.networkOperatorName ?: ""
        } catch (_: Throwable) { "" }
        current = when {
            op.contains("MTN", true)    -> MTN
            op.contains("AIRTEL", true) -> AIRTEL
            op.isBlank()                -> GENERIC
            else                        -> GENERIC.copy(name = op.uppercase())
        }
        RuntimeLogger.log("Carrier: " + op + " -> profile " + current.name +
            " baseline=" + current.expectedRttMs + "ms", "NET")
        return current
    }

    fun useWifiProfile() { current = WIFI }
}
/* ======
CarrierProfileEngine Anchor
====== */

/* ========
CongestionSentinelEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V2: reports BOTH edges. Onset warns before lag is felt; recovery (with how
 * long the dirty window lasted) tells the runtime when aggression is safe again.
 * V3: reads the override-aware wobble allowance, so an admin baseline override
 * re-tunes congestion detection on the next poll.
 */

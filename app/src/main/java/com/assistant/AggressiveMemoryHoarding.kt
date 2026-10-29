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

object AggressiveMemoryHoarding {

    private const val COOLDOWN_MS = 60_000L

    private val purgesExecuted = AtomicLong(0L)
    private val purgesSkippedCooldown = AtomicLong(0L)
    private val packagesKilled = AtomicLong(0L)
    private val killFailures = AtomicLong(0L)

    @Volatile private var lastPurgeMs = 0L
    @Volatile private var lastReclaimedMb = 0L

    /** @return true if a purge actually ran (not cooldown-skipped). */
    @JvmStatic
    fun executePurge(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPurgeMs < COOLDOWN_MS) {
            purgesSkippedCooldown.incrementAndGet()
            return false
        }
        lastPurgeMs = now
        purgesExecuted.incrementAndGet()
        RuntimeLogger.log("Initiating memory purge...", "MEMORY_HOARDER")
        // MASSIVE POWER: Force capture loop into survival mode (10fps) immediately
        // Force capture loop into survival mode (10fps) immediately
        // to prevent LMK kills while the purge runs and OS reclaims RAM.
        try { com.assistant.diagnostic.AdapterSignalBus.publishCaptureThrottle(3) } catch (_: Throwable) {}

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val before = availableMb(activityManager)

        val ownPrefix = context.packageName.substringBeforeLast('.')
        val myPid = android.os.Process.myPid()

        val candidates = LinkedHashSet<String>()
        activityManager.runningAppProcesses?.forEach { app ->
            if (app.pid != myPid &&
                app.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            ) {
                app.pkgList?.forEach { pkg ->
                    if (!pkg.startsWith(ownPrefix)) candidates.add(pkg)
                }
            }
        }

        var killed = 0
        var failed = 0
        for (pkg in candidates) {
            try {
                activityManager.killBackgroundProcesses(pkg)
                killed++
            } catch (e: Exception) {
                failed++
            }
        }
        packagesKilled.addAndGet(killed.toLong())
        killFailures.addAndGet(failed.toLong())

        val after = availableMb(activityManager)
        lastReclaimedMb = (after - before).coerceAtLeast(0L)

        RuntimeLogger.log(
            "Purge complete | killed=$killed failed=$failed | " +
                "avail ${before}MB -> ${after}MB (immediate floor; OS reclaim continues)",
            "MEMORY_HOARDER"
        )
        return true
    }

    private fun availableMb(am: ActivityManager): Long {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / 1048576L
    }

    fun hoardingRuntimeSnapshot(): Map<String, Any> = mapOf(
        "purgesExecuted" to purgesExecuted.get(),
        "purgesSkippedCooldown" to purgesSkippedCooldown.get(),
        "packagesKilled" to packagesKilled.get(),
        "killFailures" to killFailures.get(),
        "lastPurgeMs" to lastPurgeMs,
        "lastReclaimedMb" to lastReclaimedMb
    )
}
/* ======
AggressiveMemoryHoarding Anchor
====== */


/* ========
MemoryCaptureGateEngine
======== */
/**
 * MemoryCaptureGateEngine — memory pressure → capture cadence bridge.
 *
 * PROVEN GAP: AdapterSignalBus.memoryIsCritical is published but has
 * zero consumers in OverlayService's capture loop. Memory at 282MB avail
 * (CRITICAL tier) does not trigger any change in OCR cadence or frame
 * capture rate. The system keeps running 30fps OCR regardless of RAM state.
 *
 * This engine translates the memory tier into a capture_throttle level
 * that OverlayService can read to widen its frame gate:
 *
 *   HEALTHY  → captureThrottle = 0  (33ms gate, 30fps)
 *   WATCH    → captureThrottle = 1  (50ms gate, 20fps)
 *   PRESSURE → captureThrottle = 2  (66ms gate, 15fps)
 *   CRITICAL → captureThrottle = 3  (100ms gate, 10fps) + skip full VisionCore alternation
 *
 * The throttle level is a simple Int on AdapterSignalBus (added below).
 * OverlayService reads it in its frame gate check.
 *
 * Additionally publishes the current throttle state to the RuntimeLogger
 * on every tier change so the heal log captures the transition.
 */

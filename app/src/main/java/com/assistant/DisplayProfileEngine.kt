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

object DisplayProfileEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
    val gameFps: Float get() {
        val v = 60f
        return if (v > 0f) v else 30f
    }
    val gameBudgetMs: Float get() = 1000f / gameFps

    @Volatile var panelHz = 60f; private set
    @Volatile var vsyncBudgetMs = 16.67f; private set

    fun detect(ctx: Context) {
        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.displays.firstOrNull() ?: return
            panelHz = d.refreshRate
            vsyncBudgetMs = 1000f / panelHz
            RuntimeLogger.log("Display profile: panel=" + String.format("%.0f", panelHz) +
                "Hz vsyncBudget=" + String.format("%.1f", vsyncBudgetMs) +
                "ms gameBudget=" + String.format("%.1f", gameBudgetMs) + "ms", "LAGPROF")
        } catch (_: Throwable) { }
    }
}

// --- MERGED: FramePacingEngine.kt ---

// V3 AGGRESSIVE - mixture-aware, direct jitter

/**
 * V3: no single-cadence guessing. On an adaptive 90Hz panel running a 30fps
 * game, frames legally arrive at 1x/2x/3x vsync - the ENEMY is irregularity,
 * not any particular multiple. So we grade:
 *   jitterMs   - EWMA of |gap - prevGap| (beat-to-beat wobble, the felt stutter)
 *   stability  - share of frames in the window's dominant vsync bucket
 *   hard stalls - gap > 100ms (absolute; a real freeze at any cadence)
 */

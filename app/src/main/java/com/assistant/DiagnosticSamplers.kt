package com.assistant

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import com.assistant.diagnostic.LogMirror
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.sqrt

/** Read-only observation only. Does not gate or modify frame/input/engine work. */
object DiagnosticSamplers {
    private const val FPS_PERIOD_MS = 5000L
    private const val PERIODIC_MS = 30000L
    private val frameLock = Any()
    private val frameTimes = LongArray(600)
    private var frameNext = 0
    private var frameCount = 0
    private var appContext: Context? = null
    private var handler: Handler? = null
    @Volatile private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        appContext = context.applicationContext
        val thread = HandlerThread("F47-samplers", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        handler = Handler(thread.looper)
        started = true
        handler?.postDelayed(fpsReport, FPS_PERIOD_MS)
        handler?.postDelayed(periodicReport, PERIODIC_MS)
        handler?.postDelayed(networkProbe, 15000L)
    }

    /** Called at ImageReader arrival, before existing frame filtering/processing. */
    fun onCaptureFrame(timestampNanos: Long) {
        synchronized(frameLock) {
            frameTimes[frameNext] = timestampNanos
            frameNext = (frameNext + 1) % frameTimes.size
            if (frameCount < frameTimes.size) frameCount++
        }
    }

    private val fpsReport = object : Runnable {
        override fun run() {
            try {
                val values = synchronized(frameLock) {
                    LongArray(frameCount).also { out ->
                        val start = (frameNext - frameCount + frameTimes.size) % frameTimes.size
                        for (i in 0 until frameCount) out[i] = frameTimes[(start + i) % frameTimes.size]
                    }
                }
                if (values.size > 1) {
                    val gaps = LongArray(values.size - 1) { values[it + 1] - values[it] }.filter { it > 0L }
                    if (gaps.isNotEmpty()) {
                        val avg = gaps.average()
                        val sorted = gaps.sorted()
                        val p95 = sorted[(sorted.size - 1) * 95 / 100]
                        val sd = sqrt(gaps.map { (it - avg) * (it - avg) }.average())
                        val fps = 1_000_000_000.0 / avg
                        LogMirror.mirror("F47FPS", android.util.Log.INFO,
                            "samples=${gaps.size} avg_fps=${"%.2f".format(java.util.Locale.US, fps)} p95_frame_gap_ms=${"%.2f".format(java.util.Locale.US, p95 / 1_000_000.0)} jitter_stddev_ms=${"%.2f".format(java.util.Locale.US, sd / 1_000_000.0)}")
                    }
                }
            } catch (_: Throwable) { }
            handler?.postDelayed(this, FPS_PERIOD_MS)
        }
    }

    private val networkProbe = object : Runnable {
        override fun run() {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var result = "failed"
            var errorType = "none"
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("1.1.1.1", 443), 2500)
                }
                result = "connected"
            } catch (t: Throwable) {
                errorType = t.javaClass.simpleName
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            LogMirror.mirror("F47NET", android.util.Log.INFO,
                "proxy_metric=tcp_connect_rtt endpoint=1.1.1.1:443 result=$result rtt_ms=$elapsed error=$errorType")
            handler?.postDelayed(this, 15000L)
        }
    }

    private val periodicReport = object : Runnable {
        override fun run() {
            try { reportMemory() } catch (_: Throwable) { }
            try { appContext?.let { reportThermal(it) } } catch (_: Throwable) { }
            handler?.postDelayed(this, PERIODIC_MS)
        }
    }

    private fun reportMemory() {
        try {
            var rss = "unknown"
            var hwm = "unknown"
            File("/proc/self/status").forEachLine { line ->
                if (line.startsWith("VmRSS:")) rss = line.substringAfter(':').trim()
                if (line.startsWith("VmHWM:")) hwm = line.substringAfter(':').trim()
            }
            LogMirror.mirror("F47MEM", android.util.Log.INFO, "VmRSS=$rss VmHWM=$hwm")
        } catch (t: Throwable) {
            LogMirror.mirror("F47MEM", android.util.Log.WARN, "proc_status_error=${t.javaClass.simpleName}")
        }
    }

    fun onTrimMemory(level: Int) {
        LogMirror.mirror("F47MEM", android.util.Log.WARN, "onTrimMemory level=$level")
    }

    private fun reportThermal(context: Context) {
        try {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val raw = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            val temp = if (raw == Int.MIN_VALUE) "unknown" else "%.1f".format(java.util.Locale.US, raw / 10.0)
            val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus?.toString() ?: "unknown"
            } else "unavailable_api"
            LogMirror.mirror("F47THRM", android.util.Log.INFO, "battery_temp_c=$temp thermal_status=$status")
        } catch (t: Throwable) {
            LogMirror.mirror("F47THRM", android.util.Log.WARN, "read_error=${t.javaClass.simpleName}")
        }
    }
}

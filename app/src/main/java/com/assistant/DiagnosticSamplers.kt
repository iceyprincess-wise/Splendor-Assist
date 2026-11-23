package com.assistant


import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import com.assistant.diagnostic.LogMirror
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.sqrt

/** Read-only observation only. Does not gate or modify frame/input/engine work. */
object DiagnosticSamplers {
    private const val FPS_PERIOD_MS = 5000L
    private const val PERIODIC_MS = 30000L
    // No68-C-D2: the fps window is the last FPS_PERIOD_MS of arrivals, not
    // the whole 600-slot ring. The ring holds 10 s at 60 fps but 30 s at
    // 20 fps, so the old average mixed three different regimes and the
    // reported rate lagged a real drop by tens of seconds.
    private const val FPS_WINDOW_NANOS = 5_000_000_000L
    // Item 17 (Letter D, D2-F04): the lastMeasuredFps() getter is
    // DELETED — it had zero readers anywhere, and the value it returned
    // is not lost: every fpsReport path already feeds it to
    // FPSMonitor.update() (in-app display) and the F47FPS log line.
    // Item 26 upgrade 3 (Builder B): the real-measurement path below
    // ALSO publishes it to PerformanceTelemetryRegistry, so the
    // :survival LoadShedGovernor can read it — that is the first
    // decision engine to consume the measured rate. The stored field
    // below stays: it is the value those live consumers are fed from.
    @Volatile private var lastMeasuredFps = 0f
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
                // No68-C-D2: measure only the recent window. Timestamps are
                // System.nanoTime(), so the window cut is a plain subtraction.
                val nowNanos = System.nanoTime()
                val recent = values.filter { it > 0L && nowNanos - it <= FPS_WINDOW_NANOS }
                if (recent.size > 1) {
                    val gaps = LongArray(recent.size - 1) { recent[it + 1] - recent[it] }.filter { it > 0L }
                    if (gaps.isNotEmpty()) {
                        val avg = gaps.average()
                        val sorted = gaps.sorted()
                        // No68-C-D3: the old index (size-1)*95/100 reads the
                        // MINIMUM gap for tiny samples (size=2 -> index 0),
                        // so p95 understated the real tail. Round the index
                        // up so a 2-sample window reports the max gap.
                        val p95Index = ((sorted.size - 1) * 95 + 99) / 100
                        val p95 = sorted[p95Index.coerceIn(0, sorted.size - 1)]
                        val sd = sqrt(gaps.map { (it - avg) * (it - avg) }.average())
                        val fps = 1_000_000_000.0 / avg
                        lastMeasuredFps = fps.toFloat()
                        // No68-C-D1: the F47FPS line used to be the end of
                        // the road — no engine ever read it, so the measured
                        // capture rate never reached FPSMonitor (whose
                        // update() had zero callers) or any decision engine.
                        // Feed it: the in-app FPS display now shows the real
                        // measured rate instead of a permanent 0.
                        try { FPSMonitor.update(fps.toFloat()) } catch (_: Throwable) { }
                        // Item 26 upgrade 3 (Builder B): publish the real
                        // measurement for the :survival LoadShedGovernor.
                        // ONLY this branch publishes — the two zero-sample
                        // branches below must never publish: this sampler
                        // also runs in :survival (App.onCreate per process)
                        // where it never sees an arrival, so a 0 published
                        // from there would clobber the real main-process
                        // value in the shared file with a permanent 0.
                        // A dead main-process capture goes stale instead
                        // (15 s freshness window) and the governor ignores
                        // a stale/missing sample rather than reading 0.
                        try { PerformanceTelemetryRegistry.publishFrameRate(fps.toFloat()) } catch (_: Throwable) { }
                        LogMirror.mirror("F47FPS", android.util.Log.INFO,
                            "samples=${gaps.size} avg_fps=${"%.2f".format(java.util.Locale.US, fps)} p95_frame_gap_ms=${"%.2f".format(java.util.Locale.US, p95 / 1_000_000.0)} jitter_stddev_ms=${"%.2f".format(java.util.Locale.US, sd / 1_000_000.0)}")
                    } else {
                        lastMeasuredFps = 0f
                        try { FPSMonitor.update(0f) } catch (_: Throwable) { }
                        LogMirror.mirror("F47FPS", android.util.Log.INFO,
                            "samples=0 avg_fps=0.00 p95_frame_gap_ms=0.00 jitter_stddev_ms=0.00")
                    }
                } else {
                    // No68-C-D2: a dead capture used to be silent here (no
                    // line at all, so nothing distinguished "dead" from
                    // "sampler not running"). Report 0 so a dead capture is
                    // observable in the log instead of absent from it.
                    lastMeasuredFps = 0f
                    try { FPSMonitor.update(0f) } catch (_: Throwable) { }
                    LogMirror.mirror("F47FPS", android.util.Log.INFO,
                        "samples=0 avg_fps=0.00 p95_frame_gap_ms=0.00 jitter_stddev_ms=0.00")
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

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        // N7: level 20 is TRIM_MEMORY_UI_HIDDEN (routine), not pressure;
        // name it so the next audit does not misread it as an emergency.
        val name = when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN(routine)"
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "level=$level"
        }
        LogMirror.mirror("F47MEM", android.util.Log.WARN, "onTrimMemory $name")
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

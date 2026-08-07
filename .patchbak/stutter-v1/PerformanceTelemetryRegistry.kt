package com.assistant.diagnostic.registry

import android.content.Context
import java.io.File

/**
 * PERFORMANCE TELEMETRY - the bodyguard-to-king surface.
 * Adapters (:kernel process) PUBLISH measurements. The main runtime READS,
 * never waits: fresh in-memory value wins, else a throttled file read.
 */
object PerformanceTelemetryRegistry {

    data class NetTelemetry(
        val rttMs: Float, val jitterMs: Float, val quality: String,
        val carrier: String, val transport: String, val updatedMs: Long
    )

    private val EMPTY = NetTelemetry(0f, 0f, "UNKNOWN", "?", "NONE", 0L)

    @Volatile private var net: NetTelemetry = EMPTY
    @Volatile private var file: File? = null
    @Volatile private var lastRead = 0L
    @Volatile private var readCache: NetTelemetry = EMPTY

    @JvmStatic
    fun initialize(context: Context) {
        if (file == null) file = File(context.filesDir, "perf_telemetry.txt")
    }

    @JvmStatic
    @Synchronized
    fun publishNet(rttMs: Float, jitterMs: Float, quality: String, carrier: String, transport: String) {
        val t = NetTelemetry(rttMs, jitterMs, quality, carrier, transport, System.currentTimeMillis())
        net = t
        try {
            file?.writeText(t.rttMs.toString() + "|" + t.jitterMs + "|" + t.quality + "|" +
                t.carrier + "|" + t.transport + "|" + t.updatedMs)
        } catch (_: Throwable) { }
    }

    /** Non-blocking. Never touches the network, never waits on an adapter. */
    @JvmStatic
    fun currentNet(): NetTelemetry {
        val now = System.currentTimeMillis()
        if (now - net.updatedMs < 15000L) return net
        if (now - lastRead < 2000L) return readCache
        lastRead = now
        try {
            val p = (file?.takeIf { it.exists() }?.readText() ?: return readCache).split("|")
            if (p.size >= 6) readCache = NetTelemetry(
                p[0].toFloatOrNull() ?: 0f, p[1].toFloatOrNull() ?: 0f,
                p[2], p[3], p[4], p[5].toLongOrNull() ?: 0L)
        } catch (_: Throwable) { }
        return readCache
    }

    @JvmStatic
    fun netSummary(): String {
        val t = currentNet()
        return "rtt=" + t.rttMs + "ms jitter=" + t.jitterMs + "ms " + t.quality +
               " carrier=" + t.carrier + " via=" + t.transport
    }

    // ---- ACTION WINDOW (net stack verdict: GO / CAUTION / HOLD) ----
    @Volatile private var window = "UNKNOWN"
    @Volatile private var windowDetail = ""
    @Volatile private var windowMs = 0L
    @Volatile private var wfile: File? = null

    @JvmStatic
    fun publishActionWindow(verdict: String, detail: String) {
        window = verdict; windowDetail = detail; windowMs = System.currentTimeMillis()
        try {
            if (wfile == null) wfile = file?.parentFile?.let { File(it, "perf_window.txt") }
            wfile?.writeText(verdict + "|" + detail + "|" + windowMs)
        } catch (_: Throwable) { }
    }

    /** Stale-aware: a verdict older than 10s degrades to UNKNOWN. Never blocks. */
    @JvmStatic
    fun currentActionWindow(): String {
        val now = System.currentTimeMillis()
        if (now - windowMs < 10_000L) return window
        try {
            if (wfile == null) wfile = file?.parentFile?.let { File(it, "perf_window.txt") }
            val p = (wfile?.takeIf { it.exists() }?.readText() ?: return "UNKNOWN").split("|")
            if (p.size >= 3 && now - (p[2].toLongOrNull() ?: 0L) < 10_000L) return p[0]
        } catch (_: Throwable) { }
        return "UNKNOWN"
    }

    // ---- DISPLAY / DEVICE-SIDE (lag stack verdict: SMOOTH / STRAINED / CHOKING) ----
    @Volatile private var dGap = 0f
    @Volatile private var dJank = 0f
    @Volatile private var dStall = 0f
    @Volatile private var dVerdict = "UNKNOWN"
    @Volatile private var dMs = 0L
    @Volatile private var dfile: File? = null

    @JvmStatic
    fun publishDisplay(frameGapMs: Float, jankPerMin: Float, stallMs: Float, verdict: String) {
        dGap = frameGapMs; dJank = jankPerMin; dStall = stallMs; dVerdict = verdict
        dMs = System.currentTimeMillis()
        try {
            if (dfile == null) dfile = file?.parentFile?.let { File(it, "perf_display.txt") }
            dfile?.writeText(frameGapMs.toString() + "|" + jankPerMin + "|" + stallMs + "|" + verdict + "|" + dMs)
        } catch (_: Throwable) { }
    }

    /** Stale-aware, non-blocking. Older than 10s degrades to UNKNOWN. */
    @JvmStatic
    fun currentDisplayVerdict(): String {
        val now = System.currentTimeMillis()
        if (now - dMs < 10_000L) return dVerdict
        try {
            if (dfile == null) dfile = file?.parentFile?.let { File(it, "perf_display.txt") }
            val p = (dfile?.takeIf { it.exists() }?.readText() ?: return "UNKNOWN").split("|")
            if (p.size >= 5 && now - (p[4].toLongOrNull() ?: 0L) < 10_000L) return p[3]
        } catch (_: Throwable) { }
        return "UNKNOWN"
    }

    @JvmStatic
    fun displaySummary(): String =
        "gap=" + dGap + "ms jank/min=" + dJank + " stall=" + dStall + "ms " + dVerdict

    // ---- GESTURE TIMING ADVICE (lag stack -> execution authority) ----
    @Volatile private var holdMs = 0L
    @Volatile private var holdStampMs = 0L

    @JvmStatic
    fun publishGestureTiming(recommendedHoldMs: Long) {
        holdMs = recommendedHoldMs
        holdStampMs = System.currentTimeMillis()
    }

    /** 0 = no fresh advice; caller keeps its own default. Never blocks. */
    @JvmStatic
    fun recommendedHoldMs(): Long =
        if (System.currentTimeMillis() - holdStampMs < 15_000L) holdMs else 0L

    // ---- LOAD SHED (lag governor -> main-process capture pipeline) ----
    @Volatile private var shed = "NONE"
    @Volatile private var shedMs = 0L
    @Volatile private var sfile: File? = null

    @JvmStatic
    fun publishLoadShed(level: String) {
        shed = level; shedMs = System.currentTimeMillis()
        try {
            if (sfile == null) sfile = file?.parentFile?.let { File(it, "perf_loadshed.txt") }
            sfile?.writeText(level + "|" + shedMs)
        } catch (_: Throwable) { }
    }

    /** Stale-aware: advice older than 15s means the governor is gone - run full. */
    @JvmStatic
    fun currentLoadShed(): String {
        val now = System.currentTimeMillis()
        if (now - shedMs < 15_000L) return shed
        try {
            if (sfile == null) sfile = file?.parentFile?.let { File(it, "perf_loadshed.txt") }
            val p = (sfile?.takeIf { it.exists() }?.readText() ?: return "NONE").split("|")
            if (p.size >= 2 && now - (p[1].toLongOrNull() ?: 0L) < 15_000L) return p[0]
        } catch (_: Throwable) { }
        return "NONE"
    }
}

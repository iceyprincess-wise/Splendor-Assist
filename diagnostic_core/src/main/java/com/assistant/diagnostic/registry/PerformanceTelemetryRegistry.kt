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
}

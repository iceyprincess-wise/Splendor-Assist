package com.assistant.adapter.net

import android.content.Context
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures REAL round-trip latency + jitter on a live cadence and publishes it.
 * Replaces guessed constants (the old hardcoded 60ms) with measured truth.
 */
object NetProbeEngine {

    private val TARGETS = listOf("8.8.8.8" to 53, "1.1.1.1" to 53)
    private const val PROBE_EVERY_MS = 5000L
    private const val TIMEOUT_MS = 1500
    private const val ALPHA = 0.3f

    @Volatile private var running = false
    @Volatile var rtt = 0f; private set
    @Volatile var jitter = 0f; private set
    @Volatile var quality = "UNKNOWN"; private set
    @Volatile private var probes = 0L
    @Volatile private var failures = 0L

    fun start(ctx: Context) {
        if (running) return
        running = true
        PerformanceTelemetryRegistry.initialize(ctx.applicationContext)
        val t = Thread {
            var tick = 0L
            while (running) {
                probeOnce()
                if (++tick % 4L == 0L) RuntimeLogger.log(summary(), "NETPROBE")
                try { Thread.sleep(PROBE_EVERY_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-probe"; t.start()
    }

    fun stop() { running = false }

    private fun probeOnce() {
        var sample = -1L
        for ((host, port) in TARGETS) {
            sample = tcpRtt(host, port)
            if (sample >= 0) break
        }
        probes++
        if (sample < 0) {
            failures++; quality = "BAD"
        } else {
            val s = sample.toFloat()
            if (rtt == 0f) rtt = s else {
                val diff = Math.abs(s - rtt)
                jitter = jitter * (1 - ALPHA) + diff * ALPHA
                rtt = rtt * (1 - ALPHA) + s * ALPHA
            }
            val p = CarrierProfileEngine.current
            quality = when {
                rtt <= p.expectedRttMs && jitter <= p.jitterToleranceMs -> "GOOD"
                rtt <= p.expectedRttMs * 2 -> "DEGRADED"
                else -> "BAD"
            }
        }
        PerformanceTelemetryRegistry.publishNet(
            rtt, jitter, quality, CarrierProfileEngine.current.name, NetworkStateEngine.transport)
    }

    private fun tcpRtt(host: String, port: Int): Long = try {
        val t0 = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), TIMEOUT_MS) }
        (System.nanoTime() - t0) / 1_000_000L
    } catch (_: Throwable) { -1L }

    fun summary(): String =
        "rtt=" + String.format("%.0f", rtt) + "ms jitter=" + String.format("%.0f", jitter) +
        "ms " + quality + " carrier=" + CarrierProfileEngine.current.name +
        " probes=" + probes + " fail=" + failures
}

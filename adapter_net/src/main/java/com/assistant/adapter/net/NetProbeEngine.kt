package com.assistant.adapter.net

// V2 PROACTIVE
import android.content.Context
import com.assistant.admin.AdminConfigStore
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import java.net.InetSocketAddress
import java.net.Socket

/**
 * V2: median-of-3 sampling kills single-sample noise; cadence is ADAPTIVE -
 * 2s while the link is dirty, 5s when clean. On a link where jitter exceeds
 * RTT, one sample is a lie; three tell the truth.
 */
object NetProbeEngine {

    private val TARGETS = listOf("8.8.8.8" to 53, "1.1.1.1" to 53)
    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val FAST_MS: Long get() = AdminConfigStore.getLong("net.probe.fast_ms", 2000L)
    private val CALM_MS: Long get() = AdminConfigStore.getLong("net.probe.calm_ms", 5000L)
    private val TIMEOUT_MS: Int get() = AdminConfigStore.getInt("net.probe.timeout_ms", 1200)
    private val ALPHA: Float get() = AdminConfigStore.get("net.probe.alpha", 0.35f)

    @Volatile private var running = false
    @Volatile var rtt = 0f; private set
    @Volatile var jitter = 0f; private set
    @Volatile var quality = "UNKNOWN"; private set
    @Volatile var lastRawMs = -1L; private set
    @Volatile private var probes = 0L
    @Volatile private var failures = 0L

    fun start(ctx: Context) {
        if (running) return
        running = true
        PerformanceTelemetryRegistry.initialize(ctx.applicationContext)
        AdminConfigStore.initialize(ctx.applicationContext)
        val t = Thread {
            var tick = 0L
            while (running) {
                probeOnce()
                if (++tick % 6L == 0L) RuntimeLogger.log(summary(), "NETPROBE")
                val nap = if (quality == "GOOD") CALM_MS else FAST_MS
                try { Thread.sleep(nap) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-probe"; t.start()
    }

    fun stop() { running = false }

    /** single raw connect, shared with the burst mapper */
    fun rawSample(): Long {
        for ((host, port) in TARGETS) {
            val s = tcpRtt(host, port)
            if (s >= 0) return s
        }
        return -1L
    }

    private fun probeOnce() {
        val samples = ArrayList<Long>(3)
        repeat(3) {
            val s = rawSample()
            if (s >= 0) samples.add(s)
            try { Thread.sleep(60) } catch (_: Throwable) { }
        }
        probes++
        if (samples.isEmpty()) {
            failures++; quality = "BAD"; lastRawMs = -1L
        } else {
            samples.sort()
            val median = samples[samples.size / 2].toFloat()
            lastRawMs = median.toLong()
            if (rtt == 0f) rtt = median else {
                val diff = Math.abs(median - rtt)
                val a = ALPHA
                jitter = jitter * (1 - a) + diff * a
                rtt = rtt * (1 - a) + median * a
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
        "ms " + quality + " loss=" + String.format("%.0f", PacketLossProbeEngine.lossPct) +
        "% carrier=" + CarrierProfileEngine.current.name +
        " probes=" + probes + " fail=" + failures
}

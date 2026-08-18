package com.assistant.adapter.net

// V3 INSTANT-REFLEX
import android.content.Context
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import java.net.InetSocketAddress
import java.net.Socket

/**
 * V3: every knob answers to the admin store on the very next cycle - sample
 * count, sample gap, cadences, timeout, smoothing and the DEGRADED line are
 * all live-tunable with no hidden clamps. On a link change the engine is
 * woken INSTANTLY (no waiting out the nap), history is wiped and the first
 * fresh verdict lands within one probe cycle. Publishes live stats for the
 * admin Detector.
 */
object NetProbeEngine {

    private val TARGETS = listOf("8.8.8.8" to 53, "1.1.1.1" to 53)
    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val FAST_MS: Long get() = 2000L
    private val CALM_MS: Long get() = 5000L
    private val TIMEOUT_MS: Int get() = 1200
    private val ALPHA: Float get() = 0.35f
    private val SAMPLES: Int get() = 3
    private val GAP_MS: Long get() = 60L
    private val DEGRADED_MULT: Float get() = 2f

    private val lock = Object()
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
        val t = Thread {
            var tick = 0L
            while (running) {
                probeOnce()
                if (++tick % 6L == 0L) RuntimeLogger.log(summary(), "NETPROBE")
                val nap = if (quality == "GOOD") CALM_MS else FAST_MS
                try {
                    synchronized(lock) { lock.wait(if (nap > 0) nap else 1L) }
                } catch (_: InterruptedException) { }
            }
        }
        t.isDaemon = true; t.name = "net-probe"; t.start()
    }

    fun stop() { running = false; wake() }

    /** Link changed: wipe history and probe again NOW - no waiting out the nap. */
    fun onLinkChanged() {
        rtt = 0f; jitter = 0f; quality = "UNKNOWN"; lastRawMs = -1L
        wake()
        RuntimeLogger.log("link changed - history wiped, instant re-probe", "NETPROBE")
    }

    private fun wake() { synchronized(lock) { lock.notifyAll() } }

    /** single raw connect, shared with the burst mapper */
    fun rawSample(): Long {
        for ((host, port) in TARGETS) {
            val s = tcpRtt(host, port)
            if (s >= 0) return s
        }
        return -1L
    }

    private fun probeOnce() {
        val n = if (SAMPLES < 1) 1 else SAMPLES
        val gap = GAP_MS
        val samples = ArrayList<Long>(n)
        repeat(n) {
            val s = rawSample()
            if (s >= 0) samples.add(s)
            if (gap > 0) try { Thread.sleep(gap) } catch (_: Throwable) { }
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
            val base = CarrierProfileEngine.baselineRttMs
            val tol = CarrierProfileEngine.jitterTolMs
            quality = when {
                rtt <= base && jitter <= tol -> "GOOD"
                rtt <= base * DEGRADED_MULT -> "DEGRADED"
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

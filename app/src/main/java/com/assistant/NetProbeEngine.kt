package com.assistant

import android.content.Context
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import java.net.InetSocketAddress
import java.net.Socket

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
    // Phase N (N-1): D18-class double-start — the probe thread was never
    // kept, so stop() could not join it; a restart inside the 2-5 s wait
    // left TWO probers publishing concurrently. The reference is kept so
    // start() joins the old thread (500 ms) and gives up rather than
    // running two probers (Phase L L-1 pattern).
    @Volatile private var probeThread: Thread? = null
    @Volatile var rtt = 0f; private set
    @Volatile var jitter = 0f; private set
    @Volatile var quality = "UNKNOWN"; private set
    @Volatile var lastRawMs = -1L; private set
    @Volatile private var probes = 0L
    @Volatile private var failures = 0L

    /*
     * Batch 6b — fastest-wins resolver selection (the missing "best
     * connection" consumer, honestly scoped). Nothing in the repo can
     * pick a game server or a route — no game-server address exists
     * anywhere in the code — but the probe already measures TWO public
     * resolvers, and until now it used first-success (8.8.8.8 unless
     * it failed) and every other engine hardcoded 8.8.8.8. The
     * selection below measures both once per selection round and
     * publishes the faster one; RadioKeepAliveEngine sends its
     * keepalive to the winner instead of the hardcoded address.
     * A resolver is only ever a DNS endpoint, never a game route —
     * the name "preferred resolver" says exactly that.
     */
    @Volatile var preferredResolver: String = "8.8.8.8"; private set

    /*
     * Pure fastest-wins pick (pinned): the faster measured RTT wins;
     * a failed measurement (-1) loses to any success; both failed (or
     * tied) keeps the first resolver. No network access here — the
     * caller supplies the two measured values.
     */
    fun pickFaster(firstMs: Long, secondMs: Long): String {
        if (firstMs < 0 && secondMs < 0) return TARGETS[0].first
        if (firstMs < 0) return TARGETS[1].first
        if (secondMs < 0) return TARGETS[0].first
        return if (secondMs < firstMs) TARGETS[1].first else TARGETS[0].first
    }

    /** One fastest-wins selection round: measure both, publish the winner. */
    fun selectPreferredResolver() {
        val first = tcpRtt(TARGETS[0].first, TARGETS[0].second)
        val second = tcpRtt(TARGETS[1].first, TARGETS[1].second)
        val winner = pickFaster(first, second)
        if (winner != preferredResolver) {
            preferredResolver = winner
            RuntimeLogger.log("preferred resolver now $winner (8.8.8.8=${first}ms 1.1.1.1=${second}ms)", "NETPROBE")
        }
    }

    fun start(ctx: Context) {
        if (running) return
        // Phase N (N-1): a previous probe thread still sleeping out its
        // 2-5 s wait after stop() must be dead before a new one starts.
        val old = probeThread
        if (old?.isAlive == true) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        running = true
        PerformanceTelemetryRegistry.initialize(ctx.applicationContext)
        val t = Thread {
            var tick = 0L
            while (running) {
                probeOnce()
                // Batch 6b: re-select the fastest resolver on every
                // summary tick (every 6th probe) so the winner follows
                // the link instead of being a one-time guess. The
                // cadence loop itself stays first-success (rawSample)
                // — selection is a separate, bounded round.
                if (++tick % 6L == 0L) {
                    selectPreferredResolver()
                    RuntimeLogger.log(summary(), "NETPROBE")
                }
                val nap = if (quality == "GOOD") CALM_MS else FAST_MS
                try {
                    synchronized(lock) { lock.wait(if (nap > 0) nap else 1L) }
                } catch (_: InterruptedException) { }
            }
        }
        t.isDaemon = true; t.name = "net-probe"; probeThread = t; t.start()
    }

    fun stop() {
        running = false
        // Phase N (N-1): interrupt the kept thread so it exits its wait
        // now instead of sleeping out the nap; wake() notifies the lock
        // for the same reason (belt-and-braces — either suffices).
        probeThread?.interrupt()
        wake()
    }

    /** Link changed: wipe history and probe again NOW - no waiting out the nap. */
    fun onLinkChanged() {
        rtt = 0f; jitter = 0f; quality = "UNKNOWN"; lastRawMs = -1L
        // Batch 6b: a new link can flip which resolver is faster —
        // re-select immediately instead of serving the old winner.
        selectPreferredResolver()
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
        // Live ping quality was measured here but never published to the bus
        // that consumers read, so AdapterSignalBus.pingQuality stayed "UNKNOWN".
        com.assistant.diagnostic.AdapterSignalBus.publishPing(quality)
        // Item 26 (Builder B): feed the measured jitter to NetJitterEngine
        // on this probe's cadence. No new thread/socket/poll — the hook is
        // the zero-cost path; without it NetJitter would need its own poll
        // re-reading the same volatile. Tolerance is the detected carrier's
        // own (CarrierProfileEngine.jitterTolMs), not a new number.
        try { NetJitterEngine.onSample(jitter, CarrierProfileEngine.jitterTolMs.toFloat()) } catch (_: Throwable) { }
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
        " resolver=" + preferredResolver +
        " probes=" + probes + " fail=" + failures
}

package com.assistant.adapter.net

// V3 INSTANT-REFLEX
import com.assistant.admin.AdminConfigStore
import com.assistant.diagnostic.RuntimeLogger

/**
 * When the sentinel flags congestion, this fires a rapid probe burst to map
 * the spike (depth + floor) and then watches for recovery at 1s resolution -
 * so the runtime knows the link is clean again SECONDS before the slow
 * cadence would notice. V3: burst size, burst gap and the clean line are all
 * admin-tunable, and the clean line follows the override-aware baseline.
 */
object SpikeBurstEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val RECOVERY_WINDOW_MS: Long get() = AdminConfigStore.getLong("net.spike.recovery_window_ms", 60_000L)
    private val CLEAN_SAMPLES: Int get() = AdminConfigStore.getInt("net.spike.clean_samples", 2)
    private val BURST_SAMPLES: Int get() = AdminConfigStore.getInt("net.spike.burst_samples", 5)
    private val BURST_GAP_MS: Long get() = AdminConfigStore.getLong("net.spike.burst_gap_ms", 200L)
    private val CLEAN_MULT: Float get() = AdminConfigStore.get("net.spike.clean_mult", 1.5f)

    @Volatile private var running = false
    @Volatile private var mapping = false
    @Volatile private var spikes = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    if (CongestionSentinelEngine.congested && !mapping) {
                        mapping = true
                        spikes++
                        val burst = if (BURST_SAMPLES < 1) 1 else BURST_SAMPLES
                        val gap = BURST_GAP_MS
                        val samples = ArrayList<Long>(burst)
                        repeat(burst) {
                            val s = NetProbeEngine.rawSample()
                            if (s >= 0) samples.add(s)
                            if (gap > 0) try { Thread.sleep(gap) } catch (_: Throwable) { }
                        }
                        if (samples.isNotEmpty()) {
                            RuntimeLogger.log("SPIKE MAPPED depth=" + samples.max() +
                                "ms floor=" + samples.min() + "ms n=" + spikes, "NETSPIKE")
                        }
                        // recovery watch: N consecutive clean samples = clear
                        val needClean = if (CLEAN_SAMPLES < 1) 1 else CLEAN_SAMPLES
                        var clean = 0
                        val t0 = System.currentTimeMillis()
                        while (running && clean < needClean &&
                               System.currentTimeMillis() - t0 < RECOVERY_WINDOW_MS) {
                            val s = NetProbeEngine.rawSample()
                            val cleanLine = (CarrierProfileEngine.baselineRttMs * CLEAN_MULT).toLong()
                            if (s in 0..cleanLine) clean++ else clean = 0
                            try { Thread.sleep(1000) } catch (_: Throwable) { break }
                        }
                        if (clean >= needClean) {
                            val dur = (System.currentTimeMillis() - t0) / 1000L
                            RuntimeLogger.log("SPIKE RECOVERED in " + dur + "s", "NETSPIKE")
                        }
                        mapping = false
                    }
                } catch (_: Throwable) { mapping = false }
                try { Thread.sleep(1000) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-spike"; t.start()
    }

    fun stop() { running = false }
}

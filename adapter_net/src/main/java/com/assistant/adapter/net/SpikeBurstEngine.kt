package com.assistant.adapter.net

// V2 PROACTIVE
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.admin.AdminConfigStore

/**
 * When the sentinel flags congestion, this fires a rapid probe burst to map
 * the spike (depth + floor) and then watches for recovery at 1s resolution -
 * so the runtime knows the link is clean again SECONDS before the slow
 * cadence would notice. On a 30fps game, those seconds are whole plays.
 */
object SpikeBurstEngine {

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
                        val samples = ArrayList<Long>(5)
                        repeat(5) {
                            val s = NetProbeEngine.rawSample()
                            if (s >= 0) samples.add(s)
                            try { Thread.sleep(200) } catch (_: Throwable) { }
                        }
                        if (samples.isNotEmpty()) {
                            RuntimeLogger.log("SPIKE MAPPED depth=" + samples.max() +
                                "ms floor=" + samples.min() + "ms n=" + spikes, "NETSPIKE")
                        }
                        // recovery watch: N consecutive clean samples = clear
                        val cleanNeeded = AdminConfigStore.getInt("spike_clean_needed")
                        val windowMs = AdminConfigStore.getMs("spike_recovery_window_ms")
                        var clean = 0
                        val t0 = System.currentTimeMillis()
                        while (running && clean < cleanNeeded &&
                               System.currentTimeMillis() - t0 < windowMs) {
                            val s = NetProbeEngine.rawSample()
                            val p = CarrierProfileEngine.current
                            if (s in 0..(p.expectedRttMs.toLong() * 3 / 2)) clean++ else clean = 0
                            try { Thread.sleep(1000) } catch (_: Throwable) { break }
                        }
                        if (clean >= cleanNeeded) {
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

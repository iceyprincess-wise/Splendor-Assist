package com.assistant




































import com.assistant.diagnostic.RuntimeLogger












import java.net.InetAddress

















object DnsWarmupEngine {

    private val HOSTS = listOf(
        "www.konami.com", "www.google.com", "www.cloudflare.com", "one.one.one.one"
    )
    // ADMIN-TUNABLE (default = original hard-coded value)
    private val REWARM_MS: Long get() = 90_000L

    private val lock = Object()
    @Volatile private var running = false
    @Volatile private var rounds = 0L
    private val reported = HashSet<String>()

    /** Wake the loop for an immediate re-warm (called on link change). */
    fun warmNow() { synchronized(lock) { lock.notifyAll() } }

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                var ok = 0
                for (h in HOSTS) {
                    try { InetAddress.getByName(h); ok++ } catch (_: Throwable) {
                        if (reported.add(h)) RuntimeLogger.log("dns FAIL host=" + h, "NET")
                    }
                }
                rounds++
                if (rounds % 5L == 1L) RuntimeLogger.log("dns warm " + ok + "/" + HOSTS.size, "NET")
                val nap = REWARM_MS
                try {
                    synchronized(lock) { lock.wait(if (nap > 0) nap else 1L) }
                } catch (_: InterruptedException) { }
            }
        }
        t.isDaemon = true; t.name = "net-dnswarm"; t.start()
    }

    fun stop() { running = false; warmNow() }
}


/* ========
NetProbeEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V3: every knob answers to the admin store on the very next cycle - sample
 * count, sample gap, cadences, timeout, smoothing and the DEGRADED line are
 * all live-tunable with no hidden clamps. On a link change the engine is
 * woken INSTANTLY (no waiting out the nap), history is wiped and the first
 * fresh verdict lands within one probe cycle. Publishes live stats for the
 * admin Detector.
 */

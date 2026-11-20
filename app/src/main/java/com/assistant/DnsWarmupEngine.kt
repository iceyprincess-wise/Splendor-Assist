package com.assistant




































import com.assistant.diagnostic.RuntimeLogger












import java.net.InetAddress

















object DnsWarmupEngine {

    private val HOSTS = listOf(
        "www.konami.com", "www.google.com", "www.cloudflare.com", "one.one.one.one"
    )
    // Batch 6d: this was labelled as if it were admin-configurable but is a plain literal —
    // nothing can tune it. Fixed re-warm interval (original value).
    private val REWARM_MS: Long get() = 90_000L

    private val lock = Object()
    @Volatile private var running = false
    @Volatile private var rounds = 0L

    // Batch booster: pause flag, driven by FreeBoosterController. While
    // paused the loop waits on the lock instead of resolving (woken by
    // setPaused(false) / warmNow() / stop()). Pausing loses nothing — the
    // warmup only fills the OS DNS cache, and the booster pauses it only
    // while the link is degraded, to keep the radio quiet for the game.
    // The saving is small (4 resolutions per 90 s) and is not claimed
    // otherwise; the point is the principle, not the size.
    @Volatile var paused = false; private set

    fun setPaused(pause: Boolean) {
        paused = pause
        if (!pause) warmNow()
    }

    private val reported = HashSet<String>()

    /** Wake the loop for an immediate re-warm (called on link change). */
    fun warmNow() { synchronized(lock) { lock.notifyAll() } }

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                // Batch booster: while paused, wait here instead of
                // resolving. Woken by setPaused(false) / warmNow() /
                // stop(); the 1 s cap re-checks `running` so stop() can
                // never strand the thread behind a missed notify.
                synchronized(lock) {
                    while (paused && running) {
                        try { lock.wait(1000L) } catch (_: InterruptedException) { }
                    }
                }
                if (!running) break
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

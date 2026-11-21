package com.assistant




































import com.assistant.diagnostic.RuntimeLogger






























object CongestionSentinelEngine {

    // Batch 6d: these were labelled as if they were admin-configurable but are plain literals —
    // nothing anywhere can tune them (private vals, no setter, no pref).
    // Fixed constants (original hard-coded values); if admin tuning is ever
    // wanted it needs a real preference + writer first.
    private val POLL_MS: Long get() = 2000L
    private val RISE_FACTOR: Float get() = 1.5f
    private val RISE_FRACTION: Float get() = 0.6f

    @Volatile private var running = false
    @Volatile var congested = false; private set
    @Volatile var congestedSinceMs = 0L; private set
    @Volatile private var lastJitter = 0f
    @Volatile private var warnings = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val j = NetProbeEngine.jitter
                    val tol = CarrierProfileEngine.jitterTolMs.toFloat()
                    val rising = j > lastJitter * RISE_FACTOR && j > tol * RISE_FRACTION
                    val over = j > tol
                    val now = rising || over
                    if (now && !congested) {
                        congestedSinceMs = System.currentTimeMillis()
                        warnings++
                        RuntimeLogger.log("CONGESTION ONSET jitter=" + String.format("%.0f", j) +
                            "ms tol=" + tol.toInt() + "ms n=" + warnings, "NET")
                    } else if (!now && congested) {
                        val dur = (System.currentTimeMillis() - congestedSinceMs) / 1000L
                        RuntimeLogger.log("CONGESTION CLEARED after " + dur + "s jitter=" +
                            String.format("%.0f", j) + "ms", "NET")
                    }
                    congested = now
                    lastJitter = j
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-sentinel"; t.start()
    }

    fun stop() { running = false }

    // Item 16 Letter C (B1): first live reader for the onset timestamp
    // and the warning count — GameplayRoomActivity displays them on the
    // diagnostics screen. Before this the timestamp was read only by
    // this file's own CLEAR log line and the count only by the ONSET
    // log line. congestedForSecs is derived here so the display stays dumb.
    fun diagnostics(): Map<String, Any> = mapOf(
        "congested" to congested,
        "congestedForSecs" to if (congested) (System.currentTimeMillis() - congestedSinceMs) / 1000L else 0L,
        "warnings" to warnings
    )
}

/* ========
ConnectionHealEngine
======== */
/**
 * ConnectionHealEngine — Active network healer. Never accept a bad connection.
 * When HOLD is detected: WiFi rescan + rebind to best available network.
 * Cooldown: one heal per 15s (never floods the modem).
 */

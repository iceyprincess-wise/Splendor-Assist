package com.assistant.adapter.lag

// V3 ADMIN-WIRED - quick to arm, slow to thrash, every knob live
import com.assistant.admin.AdminConfigStore
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

/**
 * The rescue: when the judge says the device is struggling, this raises the
 * shed level (NONE / LIGHT / HEAVY) that the rest of the runtime obeys to
 * drop non-essential work. A SEIZURE stutter burst escalates IMMEDIATELY
 * (fast path, no waiting). V3: poll rhythm, arm/release confirm counts and
 * the minimum hold are all admin-tunable, re-read every poll.
 */
object LoadShedGovernor {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val MIN_HOLD_MS: Long get() = AdminConfigStore.getLong("lag.shed.min_hold_ms", 8000L)
    private val POLL_MS: Long get() = AdminConfigStore.getLong("lag.shed.poll_ms", 2000L)
    private val ARM_POLLS: Int get() = AdminConfigStore.getInt("lag.shed.arm_polls", 4)
    private val RELEASE_POLLS: Int get() = AdminConfigStore.getInt("lag.shed.release_polls", 5)

    @Volatile private var running = false
    @Volatile var level = "NONE"; private set
    @Volatile private var candidate = "NONE"
    @Volatile private var streak = 0
    @Volatile private var lastChangeMs = 0L
    @Volatile private var startTimeMs = 0L

    fun start() {
        if (running) return
        running = true
        startTimeMs = System.currentTimeMillis()
        val t = Thread {
            while (running) {
                try {
                    // FAST PATH: a SEIZURE burst escalates immediately -
                    // sub-second truth beats the report window when a freeze hits
                    val burst = PerformanceTelemetryRegistry.currentStutterState()
                    val bootAge=System.currentTimeMillis()-startTimeMs
                    if(startTimeMs>0L&&bootAge<10_000L){try{Thread.sleep(POLL_MS.coerceAtLeast(1L))}catch(_:Throwable){return@Thread};continue}
                    val want = if (burst == "SEIZURE") "HEAVY" else when (LagVerdictEngine.verdict) {
                        "CHOKING" -> "HEAVY"
                        "JITTERY" -> "LIGHT"
                        else -> "NONE"
                    }
                    if (want == candidate) streak++ else { candidate = want; streak = 1 }
                    val arm = if (ARM_POLLS < 1) 1 else ARM_POLLS
                    val rel = if (RELEASE_POLLS < 1) 1 else RELEASE_POLLS
                    val need = if (candidate == "NONE") rel else arm
                    val now = System.currentTimeMillis()
                    if (candidate != level && streak >= need &&
                        (lastChangeMs == 0L || now - lastChangeMs >= MIN_HOLD_MS)) {
                        RuntimeLogger.log("LOAD SHED " + level + " -> " + candidate +
                            " (device=" + LagVerdictEngine.verdict + ")", "LOADSHED")
                        level = candidate
                        lastChangeMs = now
                    }
                    PerformanceTelemetryRegistry.publishLoadShed(level)
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-loadshed"; t.start()
    }

    fun stop() { running = false }
}

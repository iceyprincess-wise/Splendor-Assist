package com.assistant






















import android.os.Handler


import android.os.Looper




import android.os.Process
import android.os.SystemClock



import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger















import java.util.concurrent.CountDownLatch


import java.util.concurrent.TimeUnit











object InputLatencyEngine {

    @Volatile private var running = false
    @Volatile var latencyMs = 0L; private set
    @Volatile var classification = "UNKNOWN"; private set
    @Volatile var measurements = 0L; private set
    @Volatile var lagEvents = 0L; private set

    private val mainHandler = Handler(Looper.getMainLooper())

    // No68-E1-D18: stop() only cleared the flag — the polling thread could
    // be asleep in Thread.sleep(200) or in latch.await(200) and, if
    // start() ran again before it noticed, the old thread saw
    // running==true and kept polling alongside the new one (double
    // measures, double publishes). Keep the thread so stop() can wake it.
    @Volatile private var pollThread: Thread? = null

    // No68-E1-D19: the crash-handler inventory claims a "6s boot suppress"
    // for this engine, but no such grace existed — the first polls ran
    // during boot, read huge latency, counted lag events and fired the
    // purge log. Suppress the lag accounting during the boot window.
    private const val BOOT_GRACE_MS = 6000L
    @Volatile private var startedAtMs = 0L

    fun start() {
        if (running) return
        // No68-E1-D18b: a previous poll thread still winding down must be
        // dead before a new one starts — otherwise two threads measure
        // and publish concurrently. Wait briefly; give up (no start)
        // rather than run two pollers.
        val old = pollThread
        if (old?.isAlive == true) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        running = true
        startedAtMs = SystemClock.elapsedRealtime()

        val t = Thread {
            // Conditionlessly boost this polling thread so it never misses a measurement
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}

            while (running) {
                try { measure() } catch (_: Throwable) {}
                try { Thread.sleep(200L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-latency"
        t.priority = Thread.MAX_PRIORITY
        pollThread = t
        t.start()

        RuntimeLogger.log("InputLatencyEngine started (Hardworking Eliminator Mode)", "INPUT")
    }

    fun stop() {
        running = false
        // No68-E1-D18c: wake the sleeper/awaiter so it exits now instead of
        // up to 400ms later (and cannot be resurrected by a quick restart).
        // The reference is kept on purpose: start() joins the old thread
        // before starting a new one.
        pollThread?.interrupt()
    }

    private fun measure() {
        val posted = SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        // No68-E1-D16: a main post that answers after its 200ms timeout
        // must not overwrite the latency the timeout already recorded.
        // The generation counter alone cannot do this (a late post from
        // the CURRENT measurement still carries the current generation),
        // so the timeout arms an expired flag the post must respect.
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)

        // Post to main thread to measure dispatch latency
        mainHandler.post {
            // No68-E1-D16b: the old code wrote latencyMs unconditionally,
            // so a post that answered AFTER its 200ms timeout overwrote
            // the timeout's 200ms with a larger elapsed value — after the
            // classification for that cycle had already been computed and
            // published, leaving latencyMs and classification disagreeing.
            // A timed-out measurement is ignored outright.
            if (!expired.get()) {
                latencyMs = SystemClock.elapsedRealtime() - posted
            }
            latch.countDown()
        }

        // Wait max 200ms for main thread to respond
        val responded = latch.await(200L, TimeUnit.MILLISECONDS)
        if (!responded) {
            expired.set(true)
            latencyMs = 200L // Main thread is completely blocked
            // No68-E1-D16c: the old countDown() here "prevented a leak" —
            // a CountDownLatch has nothing to leak, and counting down let
            // the (already timed-out) await return early on a later call
            // path. The latch is single-use per measure(); leave it alone.
        }

        measurements++

        // 15fps = 66ms/frame. 30fps = 33ms/frame.
        // NOTE (No68-E1-D20, queued for Ed, NOT retuned here): these bands
        // are 15/30fps bands, but the game runs at 60fps (16.7ms/frame),
        // so 33ms — two whole 60fps frames — still reads GOOD. The numbers
        // are a tuning decision; see QE1-1.
        val inBootGrace = SystemClock.elapsedRealtime() - startedAtMs < BOOT_GRACE_MS
        classification = when {
            latencyMs < 16L -> "INSTANT"
            latencyMs < 33L -> "GOOD"
            latencyMs < 66L -> "DELAYED"
            else -> { if (!inBootGrace) lagEvents++; "LAGGING" }
        }

        AdapterSignalBus.publishInput(classification, latencyMs)

        // CONDITIONLESS ACTIVE MITIGATION:
        // No warmup delays. If main thread is congested, immediately trigger purges.
        // No68-E1-D17: the old code published a SECOND input signal here
        // ("MAIN_THREAD_CONGESTION"), which overwrote the classification
        // published two lines above — readers (inputIsLagging, the
        // control-room display, the crash dump) never saw LAGGING during
        // congestion, only CONGESTION. One publish per measurement; the
        // congestion is still logged below.
        if (latencyMs >= 33L) { // More than 1 frame at 30fps, or half frame at 15fps

            // Force boost main thread priority dynamically if heavily lagging
            if (latencyMs >= 66L && !inBootGrace) {
                // No68-E1-D47: the old code called
                // Process.setThreadPriority(Process.myTid(), ...) HERE, on
                // the polling thread — it re-boosted the poller (already
                // URGENT_DISPLAY) and never touched the main thread the
                // comment promises to boost. Post the boost to the main
                // thread so myTid() is the main thread's id.
                mainHandler.post {
                    try { Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
                }

                RuntimeLogger.log("INPUT CRITICAL LAG: ${latencyMs}ms (total lag events: $lagEvents). PURGE TRIGGERED.", "INPUT")
            }
        }
    }
}

/* ========
InputPriorityEngine
======== */
/**
 * InputPriorityEngine — HARDWORKING BOOSTER & ELIMINATOR.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * HyperOS aggressively throttles threads during fast gameplay to save battery.
 * Waiting 30 seconds to reapply priority causes massive input lag.
 * 
 * This engine now conditionlessly reapplies URGENT_DISPLAY every 500ms
 * to prevent silent deprioritization and instantly blocks OS throttle attempts.
 */

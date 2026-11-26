package com.assistant

import android.os.Process
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

object OomAdaptiveThrottleEngine {

    // CAPTUREFIX-1 (detector correction): adj is PROCESS-level, set by
    // ActivityManager from process state. 200 is the NORMAL level for a
    // foreground service (PERCEPTIBLE_APP_ADJ) — the old threshold of 10
    // cried "hostile" at a healthy foreground service. 500 = plain
    // service, 900 = cached. Only >= 500 is hostile here.
    private const val OOM_HOSTILE_THRESHOLD = 500
    private const val POLL_MS = 500L
    private const val LOG_COOLDOWN_MS = 10_000L

    @Volatile private var running = false
    @Volatile var oomScore = 0; private set
    @Volatile var oomHostile = false; private set
    @Volatile var reapplyCount = 0; private set

    private var lastHostileLogMs = 0L

    // O-1 (Q-I1, D18-class double-start fix, mirrors Phase L L-1): the
    // poller thread is kept so stop() can interrupt it and start() can
    // join the old one instead of running two pollers. Reachability:
    // AdapterRecoveryEngine relaunches the service in-process on a stale
    // heartbeat and onCreate re-ignites the singletons.
    private var pollThread: Thread? = null

    fun start() {
        if (running) return
        val old = pollThread
        if (old != null && old.isAlive) {
            try { old.join(500) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        running = true
        val t = Thread {
            // Conditionlessly boost this polling thread immediately
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}

            while (running) {
                try { poll() } catch (_: Throwable) {}
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-oom-throttle"
        t.priority = Thread.MAX_PRIORITY
        pollThread = t
        t.start()
        RuntimeLogger.log("OomAdaptiveThrottleEngine started (Hardworking Eliminator Mode - 500ms cycle)", "INPUT")
    }

    fun stop() {
        running = false
        try { pollThread?.interrupt() } catch (_: Throwable) {}
    }

    private fun poll() {
        val adj = readOomAdj(Process.myPid())
        oomScore = adj
        val hostile = adj >= OOM_HOSTILE_THRESHOLD
        val wasHostile = oomHostile
        oomHostile = hostile

        // CONDITIONLESS ACTIVE MITIGATION (corrected — see the threshold
        // note above): thread priority CANNOT move oom_score_adj — that
        // is process-level, set by ActivityManager. What this guards is
        // silent priority resets on the two addressable worker threads:
        // this poller (myTid()) at URGENT_AUDIO (-19, as before) and the
        // MAIN thread (its Linux tid == pid) at URGENT_DISPLAY (-8, the
        // input-thread level InputPriorityEngine uses). Coroutine-pool
        // workers (vision/capture/dispatch) have no stable Thread object
        // reachable here, so their tids are not addressable through the
        // Process API and they are not re-asserted. HyperOS cgroup
        // throttling is not measurable from here and is not claimed to
        // be fixed by this reapply.
        reassertThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_AUDIO)
        reassertThreadPriority(Process.myPid(), Process.THREAD_PRIORITY_URGENT_DISPLAY)

        if (hostile) {
            // Publish hostile signal so Queen Bee (SmartAssist) can drop non-essential UI work
            // publishInput latency abuse (batch 5): the old code passed adj
            // (oom_score_adj, -1000..1000) as latencyMs. No latency is
            // measured here — the signal carries the adj value in the log
            // line below, not in the latency field — so 0L.
            AdapterSignalBus.publishInput("OOM_HOSTILE", 0L)

            val now = System.currentTimeMillis()
            if (!wasHostile || now - lastHostileLogMs > LOG_COOLDOWN_MS) {
                lastHostileLogMs = now
                RuntimeLogger.log(
                    "OOM_HOSTILE: adj=$adj — process backgrounded/cached (threshold $OOM_HOSTILE_THRESHOLD); cgroup throttling not measurable here. " +
                            "Priority reapply count=$reapplyCount",
                    "INPUT"
                )
            }
        } else {
            if (wasHostile) {
                RuntimeLogger.log("OOM_CLEAR: adj=$adj — throttle released", "INPUT")
                // Publish clear signal to restore full performance
                // (batch 5: 0L — see OOM_HOSTILE above; adj is not ms).
                AdapterSignalBus.publishInput("OOM_CLEAR", 0L)
            }
        }
    }

    // Re-assert one addressable thread's priority; counts only real
    // reapplies (a reset actually happened). tid must be a Linux tid:
    // myTid() for the current thread, myPid() for the main thread.
    private fun reassertThreadPriority(tid: Int, nice: Int) {
        try {
            if (Process.getThreadPriority(tid) > nice) {
                Process.setThreadPriority(tid, nice)
                reapplyCount++
            }
        } catch (_: Throwable) {}
    }

    private fun readOomAdj(pid: Int): Int = try {
        java.io.BufferedReader(java.io.FileReader("/proc/$pid/oom_score_adj"))
            .use { it.readLine()?.trim()?.toIntOrNull() ?: 0 }
    } catch (_: Throwable) { 0 }
}

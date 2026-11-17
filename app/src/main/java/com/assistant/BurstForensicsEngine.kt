package com.assistant



































import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger



import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.runtime.ActionClass


























object BurstForensicsEngine {
    // D-27-STUTTER producer (wires the STUTTER low ring, D-26-03):
    // a SEIZURE transition re-asserts the most recent fresh MOVE
    // stroke through HybridExecutionTerminal.route() — see
    // tryReassertMove() below. This object is the producer's live
    // trigger: StutterPulseEngine calls record() from the
    // Choreographer frame callback whenever a real burst closes a
    // 1s slice, so the producer fires on measured frame loss, never
    // on a timer or a guess.
    @Volatile private var reassertOffered = 0L
    @Volatile private var reassertAccepted = 0L
    @Volatile private var reassertRejected = 0L
    @Volatile private var reassertNoFreshMove = 0L
    init {
        // PHASE3 FIX: Publish CALM immediately so bus is not stuck at UNKNOWN
        try { AdapterSignalBus.publishStutter("CALM") } catch (_: Throwable) {}
    }

    // OMEGA FIX: Catch silent micro-stutters that ruin input timing
    // 150ms is 9 dropped frames. 45ms is 3 dropped frames (noticeable input delay).
    private val SEIZURE_MS: Float get() = 45f
    private val OSC_BURSTS: Int get() = 2
    private val OSC_WINDOW_MS: Long get() = 10000L
    private val CALM_AFTER_MS: Long get() = 5000L
    private val DECAY_POLL_MS: Long get() = 2500L

    @Volatile var state = "CALM"; private set
    @Volatile private var decayRunning = false
    @Volatile private var lastLogMs = 0L
    private val recent = ArrayDeque<Long>()   // burst timestamps, last 60s

    fun bursts60s(): Int = synchronized(this) { recent.size }

    @Synchronized
    fun record(frames: Int, worstMs: Float) {
        val now = System.currentTimeMillis()
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > 60_000L) recent.removeFirst()
        val window = if (OSC_WINDOW_MS > 0) OSC_WINDOW_MS else 1L
        val inWindow = recent.count { now - it <= window }
        val needOsc = if (OSC_BURSTS < 1) 1 else OSC_BURSTS
        val next = when {
            worstMs >= SEIZURE_MS -> "SEIZURE"
            inWindow >= needOsc -> "OSCILLATION"
            else -> "HICCUP"
        }
        val changed = next != state
        state = next
        PerformanceTelemetryRegistry.publishStutter(state, recent.size.toFloat(), worstMs)
        // CRITICAL FIX PHASE3: publishStutter was NEVER called — stutterIsSevere was always false.
        // SpeedCompensationContributor, LoadShedGovernor all read this — all were blind to stutter.
        AdapterSignalBus.publishStutter(state)
        
        // MASSIVE POWER: Performance Bee Intervention
        // SPLENDOR_P7_AGGRO_READ_BURST_BEGIN
        // Task 2C: read the cross-process registry value, not the bus.
        // FilterEngine runs in the main process, so the bus copy here was
        // always 1.0f and the "despite Filter Extremist Push" branch below
        // could never fire.
        val aggro = com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.currentFilterAggression()
        // SPLENDOR_P7_AGGRO_READ_BURST_END
        if (next == "SEIZURE") {
            try { com.assistant.diagnostic.AdapterSignalBus.publishExecutionBrake(2) } catch (_: Throwable) {}
            // SPLENDOR_P7_DEVICE_BRAKE_BURST_BEGIN
            // Mirror into the cross-process device snapshot so main-process
            // FilterEngine reports the real brake.
            try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceBrake(2) } catch (_: Throwable) {}
            // SPLENDOR_P7_DEVICE_BRAKE_BURST_END
            if (aggro > 1.5f) {
                RuntimeLogger.log("STUTTER SEIZURE: Execution brake applied despite Filter Extremist Push (${"%.2f".format(aggro)}x)", "STUTTER_BEE")
            } else {
                RuntimeLogger.log("STUTTER SEIZURE: Execution brake applied to protect SmartAssist", "STUTTER_BEE")
            }
            // D-27-STUTTER: one re-assert per SEIZURE transition, never
            // per record() call — a sustained seizure re-asserts once,
            // at onset, and only while a fresh MOVE exists to re-issue.
            if (changed) tryReassertMove()
        } else if (next == "CALM") {
            try { com.assistant.diagnostic.AdapterSignalBus.publishExecutionBrake(0) } catch (_: Throwable) {}
            // SPLENDOR_P7_DEVICE_BRAKE_BURST_CALM
            try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceBrake(0) } catch (_: Throwable) {}
        }
        if (changed) {
        }
        if (changed || now - lastLogMs >= 30_000L) {
            lastLogMs = now
            RuntimeLogger.log("burst " + state + " frames=" + frames +
                " worst=" + String.format("%.0f", worstMs) +
                "ms bursts60s=" + recent.size + " inWindow=" + inWindow, "STUTTER")
        }
    }

    /**
     * D-27-STUTTER — the STUTTER producer (D-26-03 wired).
     *
     * What it submits: a fresh ExecutionRequest with source STUTTER,
     * phase ActionClass.MOVE.ordinal and actionClass MOVE, whose
     * start/end/duration are COPIED from the most recent
     * still-unconfirmed MOVE stroke in ActionOutcomeVerifier — the
     * coordinates of a gesture this system itself decided and
     * dispatched. No coordinate is invented here. aimMotion is NOT
     * copied: the source's motion model was predicted for a frame up
     * to 350ms old, and consume() would re-run that stale prediction
     * against the endpoint; the re-assert re-issues the exact
     * dispatched coords instead.
     *
     * Why MOVE only: a MOVE stroke is a joystick hold, so re-issuing
     * it extends the hold in the same direction for one short stroke
     * (15-85ms, the source's own duration). Any other class would
     * re-fire an action (shot, pass, save) from a single decision.
     *
     * Why route(), not ContributionRegistry.offer(): offer() PARKS
     * non-emergency sources, and the only drainBest() caller is
     * RuntimeDecisionLoop (per-frame) — during a seizure frames
     * stall, so the parked entry would sit past its 350ms budget and
     * expire in the map. route() submits straight to the bus, whose
     * low ring is consumed by the accessibility engine's 9ms poll
     * whenever no higher ring and no live stroke holds the latch.
     *
     * Freshness: the new request is constructed here, so its age on
     * arrival is ~0ms against the 350ms STUTTER budget
     * (CentralExecutionBus.maxAgeMs). The SOURCE direction is bounded
     * by the same 350ms (lastFreshMove), so a direction older than
     * the bus would accept is never re-asserted.
     *
     * UNVERIFIED premise (stated, not assumed): a display seizure
     * (Choreographer gap >= 45ms) does not PROVABLY drop an
     * in-flight Accessibility gesture — once dispatched, a gesture
     * runs in the system. What a seizure provably does is stall the
     * frame pipeline, so no fresh MOVE decision arrives until frames
     * resume; this re-assert bridges that gap with the last decided
     * direction. If the stroke already landed, the re-assert is one
     * redundant same-direction hold — bounded, never a new action.
     * On-device log lines (STUTTER tag, cumulative counts) are the
     * verification surface: offered/accepted/rejected per episode.
     */
    private fun tryReassertMove() {
        val budgetNanos = CentralExecutionBus.maxAgeMs(ExecutionSource.STUTTER) * 1_000_000L
        val source = try {
            ActionOutcomeVerifier.lastFreshMove(budgetNanos)
        } catch (_: Throwable) { null }
        if (source == null) {
            reassertNoFreshMove++
            RuntimeLogger.log("STUTTER re-assert skipped: no MOVE dispatched in the last 350ms (skipped=$reassertNoFreshMove)", "STUTTER")
            return
        }
        if (!source.startX.isFinite() || !source.startY.isFinite() ||
            !source.endX.isFinite() || !source.endY.isFinite() || source.duration <= 0L) {
            reassertRejected++
            RuntimeLogger.log("STUTTER re-assert dropped: source request not finite/positive actionId=${source.actionId} (rejected=$reassertRejected)", "STUTTER")
            return
        }
        val reassert = ExecutionRequest(
            source = ExecutionSource.STUTTER,
            phase = ActionClass.MOVE.ordinal,
            actionClass = ActionClass.MOVE,
            // no59 ITEM 1: the re-assert rebuilds the request from the
            // source — carry the source's engine name with it, or the
            // dispatch log would lose the engine on this lane.
            engine = source.engine,
            startX = source.startX,
            startY = source.startY,
            endX = source.endX,
            endY = source.endY,
            duration = source.duration
        )
        reassertOffered++
        val accepted = try {
            HybridExecutionTerminal.route(reassert)
        } catch (_: Throwable) { false }
        if (accepted) {
            reassertAccepted++
            RuntimeLogger.log("STUTTER re-assert bus-accepted actionId=${reassert.actionId} sourceActionId=${source.actionId} offered=$reassertOffered accepted=$reassertAccepted", "STUTTER")
        } else {
            reassertRejected++
            RuntimeLogger.log("STUTTER re-assert bus-rejected actionId=${reassert.actionId} sourceActionId=${source.actionId} offered=$reassertOffered rejected=$reassertRejected", "STUTTER")
        }
    }

    /** decay back to CALM after an admin-tunable quiet time */
    fun startDecay() {
        if (decayRunning) return
        decayRunning = true
        val t = Thread {
            while (decayRunning) {
                try {
                    val nap = DECAY_POLL_MS
                    Thread.sleep(if (nap > 0) nap else 1L)
                    val now = System.currentTimeMillis()
                    val quiet = if (CALM_AFTER_MS > 0) CALM_AFTER_MS else 1L
                    synchronized(this) {
                        if (state != "CALM" &&
                            (recent.isEmpty() || now - recent.last() > quiet)) {
                            state = "CALM"
                            AdapterSignalBus.publishStutter("CALM")  // PHASE3 fix
                            PerformanceTelemetryRegistry.publishStutter("CALM", 0f, 0f)
                            RuntimeLogger.log("burst CALM restored", "STUTTER")
                        }
                    }
                } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "stutter-decay"; t.start()
    }

    fun stopDecay() { decayRunning = false }
}

/* ========
PanelWatchEngine
======== */
// V3 ADMIN-WIRED - NEW ENGINE: closes the adaptive-panel loophole

/**
 * Modern phone screens CHANGE their rhythm mid-game (60/90/120Hz adaptive
 * panels). The burst radar grades every frame against the screen's beat -
 * if the panel switches rhythm and nobody updates that beat, every reading
 * after the switch is graded against a lie: fake bursts on a faster panel,
 * missed real ones on a slower panel. Silent, invisible mis-detection.
 *
 * This engine closes that loophole for good: it registers the OS
 * display-change signal and pushes the new beat to the radar the INSTANT
 * the panel switches, with a belt-and-braces sweep as backup (rhythm
 * admin-tunable).
 */

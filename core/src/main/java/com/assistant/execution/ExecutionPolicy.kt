package com.assistant.execution

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.ActionClass
import kotlin.math.hypot

data class AimMotion(val originX: Float, val originY: Float, val targetX: Float, val targetY: Float,
    val relativeVx: Float, val relativeVy: Float, val predictedAtNanos: Long,
    val radius: Float, val width: Float, val height: Float,
    // LatencyBudgetLead: measured forward latency (dispatch → on-screen
    // effect) in nanos, supplied by the app layer at construction time
    // (RuntimeDecisionLoop reads PipelineLatencyMonitor.effectMeanMs()).
    // :core cannot read the app-module monitor (app depends on :core,
    // never the reverse), so the latency travels as data on the request.
    // Default 0 keeps existing call sites and tests compiling and means
    // "no measurement yet" — the age term alone, as before.
    val forwardLatencyNanos: Long = 0L) {
    fun endpoint(startX: Float, startY: Float, nowNanos: Long): Pair<Float, Float>? {
        // B24-01: the start point is copied into the compensated request
        // verbatim, so a non-finite start must reject here - the bus
        // requestIsValid check never sees it (the compensation path
        // bypasses submit-time validation). B24-04: the width/height guard
        // is <= 0, matching PredictiveState - a sub-pixel frame (0 < size
        // < 1) passes both and clamps to 0f below instead of returning null.
        if (listOf(originX, originY, targetX, targetY, relativeVx, relativeVy, radius, width, height).any { !it.isFinite() } ||
            !startX.isFinite() || !startY.isFinite() ||
            radius <= 0f || width <= 0f || height <= 0f) return null
        // LatencyBudgetLead: the lead is prediction age PLUS the measured
        // forward latency (dispatch → on-screen effect). The age term
        // covers capture → consume; the latency term covers consume →
        // effect, which the age term cannot see (endpoint runs at consume
        // time, the effect lands later). The latency is a window mean, so
        // a single spike cannot yank the lead; it is clamped to 150 ms
        // (3 frames at 20 fps) and the total lead to 250 ms, so a slow
        // pipeline cannot throw the target off-screen (the endpoint is
        // additionally clamped to screen bounds below).
        val ageNanos = (nowNanos - predictedAtNanos).coerceIn(0L, 100_000_000L)
        val latencyNanos = forwardLatencyNanos.coerceIn(0L, 150_000_000L)
        val dt = (ageNanos + latencyNanos) / 1e9f
        val dx = targetX - originX + relativeVx * dt
        val dy = targetY - originY + relativeVy * dt
        val length = hypot(dx, dy)
        if (!length.isFinite() || length <= 0f) return null
        // B24-04: a sub-pixel frame (0 < size < 1) has no interior pixel,
        // so the upper bound clamps to 0f instead of throwing on an empty
        // coerce range - the same rule PredictiveState uses (PS-D2).
        return (startX + dx / length * radius).coerceIn(0f, (width - 1f).coerceAtLeast(0f)) to
            (startY + dy / length * radius).coerceIn(0f, (height - 1f).coerceAtLeast(0f))
    }
}

/** Separate from tactical emergency panic. No settings override bypasses a blocked UI. */
object UiExecutionGate {
    @Volatile private var blockedUntilNanos = 0L
    @Volatile var reason: String = "none"; private set
    fun blocked() = System.nanoTime() < blockedUntilNanos
    // B24-02: the gate and the bus use separate monitors, so check-then-act
    // is not atomic across them. A submit that passed the gate check before
    // this block runs is wiped by clearPending() below (its verifier entry
    // is swept at 800ms with no reliability update - it never reaches the
    // policy), and a request already returned by consume() can still
    // execute after the block. Both are bounded (one in-flight request,
    // one phantom verifier entry) and no lock change can close the window,
    // because execution happens after consume() returns; the gate
    // guarantees only that no NEW queued request executes after a block.
    @Synchronized fun block(reason: String, durationMs: Long = 1500L) {
        val now = System.nanoTime()
        this.reason = reason
        RuntimeLogger.log("UiExecutionGate blocked: $reason", "UiExecutionGate")
        // SPLENDOR_P11_D04: wipe the bus queue only on a genuine transition
        // (first block after an unblocked period). The old unconditional
        // clearPending() wiped the queue on every re-block - including
        // re-blocks for the same foreground app while the bus was already
        // empty. (The 2026-11-12 session total quoted here before could not
        // be reproduced from repo evidence, so no count is stated.)
        val wasBlocked = now < blockedUntilNanos
        blockedUntilNanos = maxOf(blockedUntilNanos, now + durationMs.coerceIn(1, 5000) * 1_000_000)
        if (!wasBlocked) CentralExecutionBus.clearPending()
    }
    @Synchronized fun reset() { blockedUntilNanos = 0; reason = "none" }
}

/** Pixel macro-state correlation, never proof of internal game success. */
class OutcomePolicy {
    private data class Waiting(val phase: ActionClass, val ownerId: Int?, val hasBall: Boolean, val at: Long)
    private val pending = linkedMapOf<String, Waiting>()
    private val reliability = mutableMapOf<ActionClass, Float>()
    @Synchronized fun dispatched(id: String, phase: ActionClass, ownerId: Int?, hasBall: Boolean, now: Long) {
        if (phase == ActionClass.MOVE || phase == ActionClass.EVADE || phase == ActionClass.NONE) return
        // B24-05: the cap-64 eviction used to drop the oldest outcome
        // silently (no outcome, no reliability update). Log it so a
        // dropped verification is visible instead of silent.
        if (pending.size >= 64) {
            val evicted = pending.keys.first()
            val evictedPhase = pending[evicted]?.phase
            pending.remove(evicted)
            RuntimeLogger.log("OutcomePolicy cap evicted oldest outcome actionId=$evicted phase=$evictedPhase", "OutcomePolicy")
        }
        pending[id] = Waiting(phase, ownerId, hasBall, now)
    }
    // No64-D26: KEEPER gets a 150 ms cooling window — emergency saves must
    // be re-offerable inside one play. Queue item 6 extends the 150 ms
    // window to every other action too: at the 20 fps processed rate
    // 800 ms is 16 frames of class-wide silence after one dispatch, and
    // a second dispatch cannot clobber the first entry's outcome —
    // pending is keyed by actionId. (The 800 ms outcome-verification
    // deadline in observe() below is a different window and is unchanged.)
    @Synchronized fun cooling(phase: ActionClass, now: Long): Boolean {
        val windowNs = if (phase == ActionClass.KEEPER) 150_000_000L else 150_000_000L
        return pending.values.any { it.phase == phase && now - it.at in 0L..windowNs }
    }
    @Synchronized fun confidenceFactor(phase: ActionClass) = reliability[phase] ?: 1f
    @Synchronized fun cancel(id: String) { pending.remove(id) }
    @Synchronized fun observe(ownerId: Int?, hasBall: Boolean, fresh: Boolean, now: Long, observedAtNanos: Long = now): List<Pair<String, Boolean>> {
        val outcomes = mutableListOf<Pair<String, Boolean>>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (id, item) = iterator.next()
            val age = now - item.at
            // B24-03: the 800ms deadline judges the PIXELS, not the verify
            // call. A frame captured in-window but verified after the
            // deadline (pipeline latency) is in-window evidence. The
            // timeout below still uses verify-time age.
            val pixelAge = observedAtNanos - item.at
            val macro = fresh && observedAtNanos > item.at && pixelAge in 30_000_000L..800_000_000L && when(item.phase) {
                ActionClass.PASS, ActionClass.CROSS -> item.hasBall && hasBall && item.ownerId != null && ownerId != null && ownerId != item.ownerId
                ActionClass.DEFEND, ActionClass.KEEPER -> !item.hasBall && hasBall
                ActionClass.SHOT -> item.hasBall && !hasBall
                else -> false // Macro correlation never authenticates a goal or internal action success.
            }
            if (!macro && age <= 800_000_000L) continue
            // B24-10: the failure branch requires a trusted frame,
            // exactly as the macro-success branch already does (macro
            // starts `fresh && ...`). An untrusted frame can neither
            // confirm nor deny an outcome: its owner/ball state is not
            // trustworthy, so the age-out below would punish the engine
            // for the sensor's failure, not the action's - and in a fast
            // game a stale frame is common (lag, dropped frames), so the
            // -0.05 would systematically drag reliability down for
            // actions that may have succeeded, scaling future
            // confidences down via confidenceFactor. Holding the entry
            // instead would only defer the same penalty to the first
            // later fresh frame, whose pixels are past the 800ms window
            // and can never confirm success - so the entry is dropped
            // with NO reliability update, like cancel/abandonPending/
            // cap-64 eviction. A genuinely-failed action in a stale
            // window escapes judgement; that is the accepted cost - one
            // missed -0.05 is bounded and recoverable (+0.02 per later
            // success, floor 0.6), while systematic false penalties
            // compound. The deadline-crossing frame must be trusted.
            if (!macro && !fresh) {
                RuntimeLogger.log("OutcomePolicy age-out dropped without judgement actionId=$id phase=${item.phase} (untrusted frame)", "OutcomePolicy")
                iterator.remove()
                continue
            }
            val factor = confidenceFactor(item.phase)
            reliability[item.phase] = (factor + if (macro) 0.02f else -0.05f).coerceIn(0.6f, 1f)
            outcomes.add(id to macro); iterator.remove()
        }
        return outcomes
    }
    @Synchronized fun abandonPending() { pending.clear() }
    @Synchronized fun reset() { pending.clear(); reliability.clear() }
}

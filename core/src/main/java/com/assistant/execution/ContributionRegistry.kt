package com.assistant.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/*
 * Collection point for gameplay contributions.
 *
 * Demoted engines call offer() instead of CentralExecutionBus.submit().
 * Signature-compatible: returns Boolean, so existing `if (submitted)`
 * branches keep working unchanged.
 *
 * Latest-per-(source, phase) replacement means a repeating engine cannot
 * flood the pipeline; only its most recent opinion survives to arbitration.
 *
 * EMERGENCY LANE (wired this round - previously this lane did not exist,
 * which is exactly why the control room reported emergency submissions = 0
 * forever):
 *
 * GOALKEEPER and INTERCEPTION contributions are time-critical - the bus
 * gives them 300ms lifetimes each (CentralExecutionBus.maxAgeMs: GK 300,
 * INT 300; the 120ms / 180ms figures quoted here before exist nowhere in
 * the repo). The pending-map path only reaches the bus when the
 * SmartAssist controller cycle happens to run, which is not guaranteed to
 * occur inside those lifetimes; a save opportunity could expire in the
 * map without ever reaching the bus. Those two sources now bypass the map
 * and go straight to HybridExecutionTerminal.route(), where bus priority
 * (GK=100, INT=100) already outranks every other source.
 *
 * They are deliberately NOT also left in the pending map - one submission
 * path per request, so the same action can never dispatch twice.
 *
 * Visibility: the emergency lane carries its own counters
 * (emergencyOffered / emergencyAccepted / emergencyRejected) in the
 * runtime snapshot, and RuntimeHealthMonitor flags "emergency-rejected"
 * when a rejection has been counted - a rejected emergency submission is
 * counted and surfaced, not swallowed.
 */
object ContributionRegistry {

    private val pending = ConcurrentHashMap<String, ExecutionRequest>()

    private val offered = AtomicLong(0L)
    private val superseded = AtomicLong(0L)
    private val drained = AtomicLong(0L)
    private val expired = AtomicLong(0L)
    // Queue item 7 (F4): fresh non-best entries used to be destroyed by
    // drainBest() (droppedNonBest). They are now RETAINED in the map for
    // a later drain — the parked lane is the fallback when ConAI has no
    // best, so destroying its entries on a frame that does not need them
    // kills the fallback. droppedNonBest is kept (field + snapshot key)
    // for compatibility and is now structurally 0; retainedNonBest below
    // is the live counter. No code reads droppedNonBest (grep-proven).
    private val droppedNonBest = AtomicLong(0L)
    private val retainedNonBest = AtomicLong(0L)

    private val emergencyOffered = AtomicLong(0L)
    private val emergencyAccepted = AtomicLong(0L)
    private val emergencyRejected = AtomicLong(0L)

    private fun key(request: ExecutionRequest): String =
        "${request.source}:${request.phase}"

    private fun isEmergencySource(source: ExecutionSource): Boolean =
        source == ExecutionSource.GOALKEEPER ||
            source == ExecutionSource.INTERCEPTION

    // D12 return contract: emergency sources return the bus result (true =
    // accepted, false = rejected and counted in emergencyRejected).
    // Non-emergency sources are PARKED and always return false - false
    // here means "parked, not dispatched", not "failed": callers must not
    // set cooldowns on it (see the note below). `offered` counts every
    // offer including emergency ones; emergencyOffered is the emergency
    // subset, not a second count of the same offer.
    // D13: offer/drainBest/clear share this monitor. Lock order is
    // registry -> bus (offer calls route() -> submit() under this monitor);
    // no bus-held path ever takes the registry monitor, so no cycle.
    @Synchronized fun offer(request: ExecutionRequest): Boolean {
        if (isEmergencySource(request.source)) {
            emergencyOffered.incrementAndGet()
            offered.incrementAndGet()
            val accepted = HybridExecutionTerminal.route(request)
            if (accepted) {
                emergencyAccepted.incrementAndGet()
            } else {
                emergencyRejected.incrementAndGet()
            }
            return accepted
        }

        val k = key(request)
        if (pending.put(k, request) != null) {
            superseded.incrementAndGet()
        }
        offered.incrementAndGet()
        return false // parked, not dispatched: callers must not set cooldowns
    }

    /*
     * Returns the highest-priority fresh contribution.
     *
     * Queue item 7 (F4): a drained-but-unpicked entry used to be destroyed
     * here even though it was still fresh — the parked lane is the fallback
     * when ConAI has no best, so destroying its entries on a frame that
     * does not need them kills the fallback. Expired entries are removed;
     * the best fresh entry is removed and returned; every other fresh
     * entry STAYS parked for a later drain. Bounded: one entry per
     * (source, phase) key (latest-wins replacement), and the 250/300 ms
     * bus staleness budget expires a retained entry on the next drain.
     * Only the caller that needs the fallback drains (RDL drains lazily,
     * only when best == null), so a retained entry is not consumed-and-
     * dropped by a frame that already has a live best.
     */
    @Synchronized fun drainBest(): ExecutionRequest? {
        if (pending.isEmpty()) return null

        // D4/D5: freshness uses the bus's own staleness budget
        // (CentralExecutionBus.maxAgeMs, per source) on the monotonic
        // clock (createdAtNanos), not a wall-clock 400ms window. The old
        // 400ms wall-clock window let a 300ms-old SMART_ASSIST entry drain
        // as "fresh" and then be stale-rejected at submit, and a clock
        // jump could expire a fresh entry or revive a stale one. The bus
        // timestamp field is now record-only repo-wide.
        val nowNanos = System.nanoTime()
        // F37-D8: per-call loss deltas, for the drain log below.
        val expiredBefore = expired.get()
        val retainedBefore = retainedNonBest.get()
        var best: ExecutionRequest? = null
        var freshCount = 0
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val candidate = entry.value
            if (nowNanos - candidate.createdAtNanos > CentralExecutionBus.maxAgeMs(candidate.source) * 1_000_000L) {
                iterator.remove()
                expired.incrementAndGet()
                continue
            }
            freshCount++
            val current = best
            if (current == null ||
                HybridExecutionTerminal.priority(candidate.source) >
                HybridExecutionTerminal.priority(current.source)
            ) {
                best = candidate
            }
        }

        // The returned entry leaves the map (remove by key+value, so a
        // re-offer of the same key parked after this drain is not
        // clobbered). Fresh entries that did not win STAY parked; count
        // them so a retained fallback is visible instead of silent.
        if (best != null) {
            pending.remove(key(best), best)
            drained.incrementAndGet()
        }
        val retainedNow = (freshCount - if (best != null) 1 else 0).toLong()
        if (retainedNow > 0L) retainedNonBest.addAndGet(retainedNow)
        // F37-D8: expiry losses were counted but never logged, so a parked
        // request could vanish with no trace in the bundle. Retention is
        // logged with the same line when it happens alongside an expiry.
        val expiredDelta = expired.get() - expiredBefore
        val retainedDelta = retainedNonBest.get() - retainedBefore
        if (expiredDelta > 0L || retainedDelta > 0L) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "ContributionRegistry drain dropped expired=$expiredDelta retainedNonBest=$retainedDelta", "CONTRIBUTION"
            )
        }
        return best
    }

    @Synchronized fun clear() {
        pending.clear()
        // D9: clear() is the reset path (RuntimeCoordinator.resetRuntimeState).
        // The counters are per-session runtime state, and the health
        // latches (busAlive / gameplayAlive) read "offered > 0" - leaving
        // them set would keep the latches true forever after a reset, so
        // "bus-idle" / "gameplay-idle" could never fire again.
        offered.set(0L)
        superseded.set(0L)
        drained.set(0L)
        expired.set(0L)
        droppedNonBest.set(0L)
        retainedNonBest.set(0L)
        emergencyOffered.set(0L)
        emergencyAccepted.set(0L)
        emergencyRejected.set(0L)
    }

    // D8: the bus statistics are read ONCE into a local val, so the
    // snapshot is a single instant - the old code read statistics.value
    // eight separate times and could mix two different instants.
    fun contributionRuntimeSnapshot(): Map<String, Any> {
        val stats = CentralExecutionBus.statistics.value
        return mapOf(
            "offered" to offered.get(),
            "superseded" to superseded.get(),
            "drained" to drained.get(),
            "expired" to expired.get(),
            "droppedNonBest" to droppedNonBest.get(),
            // Queue item 7 (F4): fresh non-best entries are retained, not
            // dropped — droppedNonBest above is now structurally 0 and
            // this is the live retained count.
            "retainedNonBest" to retainedNonBest.get(),
            "pending" to pending.size,
            // Bus statistics are read here so the CentralExecutionBus flow has
            // a live consumer (MainActivity / RuntimeHealthMonitor / control
            // rooms read this snapshot); previously the flow was never collected.
            "busAccepted" to stats.acceptedCount,
            "busConsumed" to stats.consumedCount,
            "busPending" to stats.pendingCount,
            "busStoppedDrops" to stats.stoppedDrops,
            // Batch 6c (v219 item a): ui-blocked drops are counted
            // separately from stopped drops (CentralExecutionBus).
            "busUiBlockedDrops" to stats.uiBlockedDrops,
            "busInvalidDrops" to stats.invalidDrops,
            "busStaleDrops" to stats.staleDrops,
            "busCapacityDrops" to stats.capacityDrops,
            "busReservationDrops" to stats.reservationDrops,
            "busAimNull" to stats.aimNullCompensations,
            "emergencyOffered" to emergencyOffered.get(),
            "emergencyAccepted" to emergencyAccepted.get(),
            "emergencyRejected" to emergencyRejected.get()
        )
    }
}

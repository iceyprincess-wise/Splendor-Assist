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
 * gives them 120ms / 180ms lifetimes. The pending-map path only reaches
 * the bus when the SmartAssist controller cycle happens to run, which is
 * not guaranteed to occur inside those lifetimes; a save opportunity could
 * expire in the map without ever reaching the bus. Those two sources now
 * bypass the map and go straight to HybridExecutionTerminal.route(), where
 * bus priority (GK=100, INT=90) already outranks every other source.
 *
 * They are deliberately NOT also left in the pending map - one submission
 * path per request, so the same action can never dispatch twice.
 *
 * Visibility: the emergency lane carries its own counters
 * (emergencyOffered / emergencyAccepted / emergencyRejected) in the
 * runtime snapshot, so a silent failure on this lane is impossible to
 * miss - a rejected emergency submission is counted, not swallowed.
 */
object ContributionRegistry {

    private val pending = ConcurrentHashMap<String, ExecutionRequest>()

    private val offered = AtomicLong(0L)
    private val superseded = AtomicLong(0L)
    private val drained = AtomicLong(0L)
    private val expired = AtomicLong(0L)

    private val emergencyOffered = AtomicLong(0L)
    private val emergencyAccepted = AtomicLong(0L)
    private val emergencyRejected = AtomicLong(0L)

    private const val MAX_AGE_MS = 400L

    private fun key(request: ExecutionRequest): String =
        "${request.source}:${request.phase}"

    private fun isEmergencySource(source: ExecutionSource): Boolean =
        source == ExecutionSource.GOALKEEPER ||
            source == ExecutionSource.INTERCEPTION

    fun offer(request: ExecutionRequest): Boolean {
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
        return true
    }

    /*
     * Returns the highest-priority fresh contribution and clears the rest.
     * Called once per controller cycle by the single normal submitter.
     * Emergency-lane requests never appear here - they have already been
     * routed to the bus at offer() time.
     */
    fun drainBest(): ExecutionRequest? {
        if (pending.isEmpty()) return null

        val now = System.currentTimeMillis()
        val snapshot = pending.entries.toList()
        pending.clear()

        var best: ExecutionRequest? = null
        for (entry in snapshot) {
            val candidate = entry.value
            if (now - candidate.timestamp > MAX_AGE_MS) {
                expired.incrementAndGet()
                continue
            }
            val current = best
            if (current == null ||
                HybridExecutionTerminal.priority(candidate.source) >
                HybridExecutionTerminal.priority(current.source)
            ) {
                best = candidate
            }
        }

        if (best != null) {
            drained.incrementAndGet()
        }
        return best
    }

    fun clear() {
        pending.clear()
    }

    fun contributionRuntimeSnapshot(): Map<String, Any> = mapOf(
        "offered" to offered.get(),
        "superseded" to superseded.get(),
        "drained" to drained.get(),
        "expired" to expired.get(),
        "pending" to pending.size,
        "emergencyOffered" to emergencyOffered.get(),
        "emergencyAccepted" to emergencyAccepted.get(),
        "emergencyRejected" to emergencyRejected.get()
    )
}

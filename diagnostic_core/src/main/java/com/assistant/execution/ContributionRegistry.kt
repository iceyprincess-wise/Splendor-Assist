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
 */
object ContributionRegistry {

    private val pending = ConcurrentHashMap<String, ExecutionRequest>()

    private val offered = AtomicLong(0L)
    private val superseded = AtomicLong(0L)
    private val drained = AtomicLong(0L)
    private val expired = AtomicLong(0L)

    private const val MAX_AGE_MS = 400L

    private fun key(request: ExecutionRequest): String =
        "${request.source}:${request.phase}"

    fun offer(request: ExecutionRequest): Boolean {
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
        "pending" to pending.size
    )
}

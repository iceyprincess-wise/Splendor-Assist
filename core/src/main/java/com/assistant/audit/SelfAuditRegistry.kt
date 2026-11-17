package com.assistant.audit

/**
 * Live audit of the overlay node, as reported by OverlaySurvivalEngine.
 *
 * Contract (no48 round):
 * - "Verified" is defined from the only status vocabulary the sole
 *   caller reports (OverlaySurvivalEngine): ATTACHED is the healthy
 *   state; DESTROYED is not verified. DETACHED used to be part of the
 *   vocabulary, but OverlaySurvivalEngine.detached() had no call site
 *   and none can exist — the overlay view is removed only in
 *   OverlayService.onDestroy(), which reports DESTROYED — so
 *   detached() was removed (No48-D7) and DETACHED is no longer a
 *   status this registry can see.
 * - No48-D1: the audit state is DERIVED from the counters, not
 *   stored. The old `private var state = "UNVERIFIED"` was never
 *   written by anyone in the repo (repo-wide grep: no writer exists
 *   outside this file, and this file never wrote it either), so
 *   snapshot() always printed "Audit : UNVERIFIED" even while the
 *   overlay was attached and Verified showed 1/1. Now: no nodes yet
 *   -> UNVERIFIED; every node ATTACHED -> VERIFIED; some ATTACHED
 *   -> PARTIAL; none ATTACHED -> DOWN. snapshot() is display-only
 *   (DashboardInjector appends it verbatim to a TextView; nothing
 *   parses it), so the new words need no parser update.
 * - Counters: update() has always written verifiedNodes/totalNodes
 *   in the repo's history (the file arrived in da33fcb already
 *   writing them). The old "Previously both counters stayed 0/0
 *   forever because update() never wrote them" comment described a
 *   pre-repo state that cannot be verified from git history, so it
 *   is replaced by this present-tense contract (No48-D2).
 * - nodeStates holds one entry per distinct node name and is never
 *   cleared; only "OVERLAY" is ever reported today, so it holds one
 *   entry in practice. If a future caller reports per-engine nodes,
 *   add a removeNode() then — an unused removal API today would
 *   itself be dead code (No48-D5).
 * - @Volatile on the counters is redundant with @Synchronized (the
 *   lock already gives visibility), but harmless; left as-is so a
 *   future unsynchronised reader still sees fresh values (No48-D4).
 * - Snapshot node lines are emitted in sorted key order so the
 *   output is deterministic run to run (No48-D8); ConcurrentHashMap
 *   iteration order is unspecified.
 */
object SelfAuditRegistry {

    @Volatile
    private var verifiedNodes = 0

    @Volatile
    private var totalNodes = 0

    private val nodeStates =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Records the latest status of one node. Returns true when the
     * update was recorded, false when it was refused.
     *
     * No48-D3: a blank node name used to be accepted — it would create
     * an entry nobody can ever read back by name and inflate
     * totalNodes. It is now refused. The refusal is deliberately NOT
     * logged: this registry is a leaf object with zero Android
     * dependencies today (pure Kotlin + ConcurrentHashMap), and
     * pulling RuntimeLogger in would drag file I/O and LogMirror into
     * every update() and break its plain-JVM testability. The caller
     * gets the false return instead; the only caller passes constants.
     */
    @Synchronized
    fun update(
        node: String,
        status: String
    ): Boolean {
        if (node.isBlank()) {
            return false
        }
        nodeStates[node] = status
        totalNodes = nodeStates.size
        verifiedNodes = nodeStates.values.count { it == "ATTACHED" }
        return true
    }

    @Synchronized
    fun snapshot(): String {

        // No48-D1: state is derived from the live counters on every
        // snapshot, so it can never go stale again.
        val state = when {
            totalNodes == 0 -> "UNVERIFIED"
            verifiedNodes == totalNodes -> "VERIFIED"
            verifiedNodes == 0 -> "DOWN"
            else -> "PARTIAL"
        }

        return buildString {

            append("Audit : ")
            append(state)

            append("\nVerified : ")
            append(verifiedNodes)

            append("/")

            append(totalNodes)

            if (nodeStates.isNotEmpty()) {

                append("\n\n")

                nodeStates.toSortedMap().forEach {
                    append(it.key)
                    append(" : ")
                    append(it.value)
                    append("\n")
                }
            }
        }
    }
}

package com.assistant

/**
 * L7 — Memory Attribution Producer (MemoryAttributionEngine).
 *
 * Gap (Q-N7-3): the next memory event could not be attributed — the
 * system-avail drop (1278 → 361 MB) was unattributed and the app's own
 * RSS (≈405 MB worst case) could not explain a ~900 MB drop.
 *
 * Contract: in = per-process RSS timeline + allocation counters; out =
 * attribution log lines (`availDrop: game=<…> app=<…> system=<…>`);
 * caller = MemoryMonitorEngine (its 5 s poll, on every avail drop);
 * consumer = the log + the next log-proof round.
 *
 * Diagnostic producer: it only attributes and logs; it never frees,
 * throttles, or changes a decision. The math is a split of the observed
 * avail drop by each process's PSS growth since the previous sample:
 * a process that grew consumed avail, one that shrank freed it (counted
 * as 0, never negative — a shrink cannot explain a drop). Whatever the
 * known processes cannot explain is `system` (remainder, explicitly
 * including any process whose PSS was unreadable: a -1 PSS contributes
 * 0 and sets unknown=true, so the remainder is honest about what it
 * still hides). A non-positive drop is not an attribution at all.
 *
 * Pure Kotlin (no Android): the Android side (Debug.getPss /
 * ActivityManager.getProcessMemoryInfo) lives in MemoryMonitorEngine,
 * which passes plain numbers in. Pinned in the batch-1 harness (group 20).
 */
data class MemoryAttribution(
    val availDropMb: Long,
    val appMb: Long,
    val survivalMb: Long,
    val gameMb: Long,
    val systemMb: Long,
    val unknown: Boolean
)

object MemoryAttributionEngine {

    /**
     * Splits an avail drop across the three known processes. Any PSS of
     * -1 means "unreadable on this sample" (Android returns no data for
     * another uid's process): it contributes 0 and flags unknown.
     */
    fun attribute(
        prevAvailMb: Long,
        availMb: Long,
        prevAppPssMb: Long,
        appPssMb: Long,
        prevSurvivalPssMb: Long,
        survivalPssMb: Long,
        prevGamePssMb: Long,
        gamePssMb: Long
    ): MemoryAttribution {
        val drop = prevAvailMb - availMb
        if (drop <= 0L) return MemoryAttribution(0L, 0L, 0L, 0L, 0L, false)
        fun growth(prev: Long, now: Long): Long =
            if (prev < 0L || now < 0L) 0L else (now - prev).coerceAtLeast(0L)
        val app = growth(prevAppPssMb, appPssMb)
        val survival = growth(prevSurvivalPssMb, survivalPssMb)
        val game = growth(prevGamePssMb, gamePssMb)
        val unknown = prevAppPssMb < 0L || appPssMb < 0L ||
            prevSurvivalPssMb < 0L || survivalPssMb < 0L ||
            prevGamePssMb < 0L || gamePssMb < 0L
        // The remainder is system + every unreadable process: it is a
        // remainder by construction, never a measured system value.
        val system = (drop - app - survival - game).coerceAtLeast(0L)
        return MemoryAttribution(drop, app, survival, game, system, unknown)
    }

    fun logLine(attribution: MemoryAttribution): String =
        "MEM_ATTR availDrop=${attribution.availDropMb}MB " +
            "app=${attribution.appMb}MB survival=${attribution.survivalMb}MB " +
            "game=${attribution.gameMb}MB system=${attribution.systemMb}MB" +
            if (attribution.unknown) " unknown=1" else ""
}

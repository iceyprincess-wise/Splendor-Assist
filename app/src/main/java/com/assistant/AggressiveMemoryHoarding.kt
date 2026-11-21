package com.assistant


import android.app.ActivityManager




import android.content.Context






















import android.os.Process




import com.assistant.diagnostic.RuntimeLogger




















import java.util.concurrent.atomic.AtomicLong









object AggressiveMemoryHoarding {

    private const val COOLDOWN_MS = 60_000L

    private val purgesExecuted = AtomicLong(0L)
    private val purgesSkippedCooldown = AtomicLong(0L)
    private val packagesKilled = AtomicLong(0L)
    private val killFailures = AtomicLong(0L)

    @Volatile private var lastPurgeMs = 0L
    @Volatile private var lastReclaimedMb = 0L

    /** @return true if a purge actually ran (not cooldown-skipped). */
    @JvmStatic
    fun executePurge(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPurgeMs < COOLDOWN_MS) {
            purgesSkippedCooldown.incrementAndGet()
            return false
        }
        lastPurgeMs = now
        purgesExecuted.incrementAndGet()
        RuntimeLogger.log("Initiating memory purge...", "MEMORY_HOARDER")
        // Letter A item 18: the publishCaptureThrottle(3) that used to sit
        // here is REMOVED. It set the bus field without touching
        // MemoryCaptureGateEngine.captureThrottle, which is what the
        // capture loop actually reads (OverlayService + recommendedIntervalMs),
        // so it never slowed capture — it only made the dashboard
        // (GameplayRoomActivity) display throttle 3 after a purge. The live
        // throttle is driven solely by MemoryCaptureGateEngine.onTierChange.
        // The purge frees RAM by killing background processes; it does not
        // slow capture.

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val before = availableMb(activityManager)

        val ownPrefix = context.packageName.substringBeforeLast('.')
        val myPid = android.os.Process.myPid()

        val candidates = LinkedHashSet<String>()
        activityManager.runningAppProcesses?.forEach { app ->
            if (app.pid != myPid &&
                app.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            ) {
                app.pkgList?.forEach { pkg ->
                    if (!pkg.startsWith(ownPrefix)) candidates.add(pkg)
                }
            }
        }

        var killed = 0
        var failed = 0
        for (pkg in candidates) {
            try {
                activityManager.killBackgroundProcesses(pkg)
                killed++
            } catch (e: Exception) {
                failed++
            }
        }
        packagesKilled.addAndGet(killed.toLong())
        killFailures.addAndGet(failed.toLong())

        val after = availableMb(activityManager)
        lastReclaimedMb = (after - before).coerceAtLeast(0L)

        RuntimeLogger.log(
            "Purge complete | killed=$killed failed=$failed | " +
                "avail ${before}MB -> ${after}MB (immediate floor; OS reclaim continues)",
            "MEMORY_HOARDER"
        )
        return true
    }

    private fun availableMb(am: ActivityManager): Long {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / 1048576L
    }

    // Letter A item 18: hoardingRuntimeSnapshot() is DELETED (was here).
    // It had zero callers repo-wide, so its six map keys (purgesExecuted,
    // purgesSkippedCooldown, packagesKilled, killFailures, lastPurgeMs,
    // lastReclaimedMb) were never read. The counters still exist and are
    // still logged by executePurge; only the unread snapshot is gone.

}


/* ========
MemoryCaptureGateEngine
======== */
/**
 * MemoryCaptureGateEngine — memory pressure → capture cadence bridge.
 *
 * PROVEN GAP: AdapterSignalBus.memoryIsCritical is published but has
 * zero consumers in OverlayService's capture loop. Memory at 282MB avail
 * (CRITICAL tier) does not trigger any change in OCR cadence or frame
 * capture rate. The system keeps running 30fps OCR regardless of RAM state.
 *
 * This engine translates the memory tier into a capture_throttle level
 * that OverlayService can read to widen its frame gate:
 *
 *   HEALTHY  → captureThrottle = 0  (33ms gate, 30fps)
 *   WATCH    → captureThrottle = 1  (50ms gate, 20fps)
 *   PRESSURE → captureThrottle = 2  (66ms gate, 15fps)
 *   CRITICAL → captureThrottle = 3  (100ms gate, 10fps) + skip full VisionCore alternation
 *
 * The throttle level is a simple Int on AdapterSignalBus (added below).
 * OverlayService reads it in its frame gate check.
 *
 * Additionally publishes the current throttle state to the RuntimeLogger
 * on every tier change so the heal log captures the transition.
 */

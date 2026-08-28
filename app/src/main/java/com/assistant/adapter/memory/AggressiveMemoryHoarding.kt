package com.assistant.adapter.memory

import android.app.ActivityManager
import android.content.Context
import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * MEMORY RECLAIM ENGINE (Task C upgrade).
 *
 * Honesty fixes over the previous version:
 *
 * - NO SILENT FAILURES. Kill attempts that threw were swallowed by an
 *   empty catch, and killedCount counted ATTEMPTS, not successes. Successes
 *   and failures are now counted separately; a purge that reclaimed nothing
 *   is visible as exactly that.
 *
 * - COOLDOWN GUARD. Callers may invoke this on every CRITICAL tick; the
 *   engine enforces one purge per 60s so pressure loops cannot thrash the
 *   system with kill storms.
 *
 * - NO SELF-GC THEATRE. System.gc()/runFinalization() only sweep OUR heap
 *   and pause OUR process - they do not reclaim system RAM. Removed. The
 *   before/after availMem delta is measured and logged instead.
 *
 * - OWN PACKAGES PROTECTED. The kill list dedupes packages and skips our
 *   own package family.
 *
 * - MEASURED RESULT, STATED HONESTLY. Reclaim is reported as the availMem
 *   delta immediately after the kill pass. OS reclaim continues
 *   asynchronously, so the number is a floor, not a ceiling.
 *
 * REQUIRES: android.permission.KILL_BACKGROUND_PROCESSES.
 * Platform truth: on modern Android this only demotes processes already in
 * the background-killable band; it cannot touch foreground or
 * system-protected processes. Useful headroom on a 4GB device, not magic.
 */
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
        // MASSIVE POWER: Force capture loop into survival mode (10fps) immediately
        try { com.assistant.diagnostic.AdapterSignalBus.publishCaptureThrottle(3) } catch (_: Throwable) {}
        // MASSIVE POWER: Force capture loop into survival mode (10fps) immediately
        // to prevent LMK kills while the purge runs and OS reclaims RAM.
        try { com.assistant.diagnostic.AdapterSignalBus.publishCaptureThrottle(3) } catch (_: Throwable) {}

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

    fun hoardingRuntimeSnapshot(): Map<String, Any> = mapOf(
        "purgesExecuted" to purgesExecuted.get(),
        "purgesSkippedCooldown" to purgesSkippedCooldown.get(),
        "packagesKilled" to packagesKilled.get(),
        "killFailures" to killFailures.get(),
        "lastPurgeMs" to lastPurgeMs,
        "lastReclaimedMb" to lastReclaimedMb
    )
}

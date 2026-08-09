package com.assistant.adapter.memory

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * PRESSURE-TIERED MEMORY ADAPTER NODE (Task C upgrade).
 *
 * Every change below is traced against the real target device: 4GB RAM,
 * 50-60% baseline use - permanently one bad allocation away from LMK kills.
 *
 * 1. TIERED PRESSURE RESPONSE. The old node polled every 30s (15s under
 *    pressure). On a RAM-starved device, low-memory kills happen within
 *    seconds; a 30s blind spot is how sessions die "for no reason".
 *    Polling now adapts across four tiers - HEALTHY 30s / WATCH 10s /
 *    PRESSURE 5s / CRITICAL 2s - driven by availMem vs the OS kill
 *    threshold.
 *
 * 2. REAL RECOVERY, NOT SELF-GC. The old "recovery" was System.gc() +
 *    runFinalization() in OUR OWN process. That frees almost nothing
 *    system-wide and pauses our own hot path - self-inflicted stutter
 *    dressed up as recovery. Removed. On CRITICAL the node now triggers
 *    AggressiveMemoryHoarding.executePurge (cooldown-guarded inside the
 *    engine), which acts on the actual RAM consumers. Loophole closed:
 *    that engine existed but the pressure path NEVER invoked it - it was
 *    dead code presented as capability.
 *
 * 3. onTrimMemory WIRED. The OS's own pressure signal (ComponentCallbacks2)
 *    was ignored entirely. Any trim signal at RUNNING_LOW or worse now
 *    forces an immediate telemetry pass instead of waiting out the timer.
 *    An epoch guard ensures a forced pass replaces the pending chain
 *    rather than forking a second polling loop.
 *
 * 4. NOTIFICATION ID COLLISION FIXED. This service and InputAdapterService
 *    both called startForeground(9993). Same app, same ID: the second
 *    notification silently replaces the first, and stopping either service
 *    can strip the other's foreground status - turning it into LMK bait.
 *    Memory now owns 9994. (Full notification unification is item (c).)
 *
 * 5. TRUTHFUL HEALTH. Health details now carry the live tier and availMB,
 *    not a static "Heartbeat active" string.
 */
class MemoryAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { true })

    private val executorService: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    private lateinit var activityManager: ActivityManager

    // Cached to eliminate per-tick allocation churn
    private val memoryInfo = ActivityManager.MemoryInfo()

    private val errorCount = AtomicInteger(0)
    private val recoveryCount = AtomicInteger(0)
    private val telemetryEpoch = AtomicInteger(0)

    @Volatile private var isRunning = false
    @Volatile private var currentTier = "UNKNOWN"
    @Volatile private var lastAvailableMb = -1L

    private enum class Tier(val pollMs: Long) {
        HEALTHY(30_000L),
        WATCH(10_000L),
        PRESSURE(5_000L),
        CRITICAL(2_000L)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        RuntimeLogger.log("MemoryAdapterService started: PRESSURE-TIERED NODE", "ADAPTER")

        setupForegroundService()
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        publishHealth("Foreground service running - tiered pressure response")

        scheduleNextHeartbeat(10_000L)
        forceTelemetryPass(500L)
    }

    private fun setupForegroundService() {
        val channelId = "memory_adapter"
        val channel = NotificationChannel(
            channelId,
            "Memory Core Engine",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Pressure-tiered memory node"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Splendor Memory Node")
            .setContentText("Active Memory Stabilization")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        // 9994: unique. 9993 collided with InputAdapterService (see class doc #4).
        startForeground(9994, notification)
    }

    /*
     * OS pressure signal. RUNNING_LOW or worse means the timer is already
     * too slow - measure NOW.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        RuntimeLogger.log("OS trim signal level=$level", "MEMORY")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            forceTelemetryPass(0L)
        }
    }

    /* Starts a fresh telemetry chain; any pending older chain dies via epoch. */
    private fun forceTelemetryPass(delayMs: Long) {
        scheduleTelemetry(delayMs, telemetryEpoch.incrementAndGet())
    }

    private fun scheduleTelemetry(delayMs: Long, epoch: Int) {
        if (!isRunning) return
        executorService.schedule({
            if (!isRunning || epoch != telemetryEpoch.get()) return@schedule
            try {
                // Populate cached memory object - zero allocation per tick
                activityManager.getMemoryInfo(memoryInfo)

                val availableMb = memoryInfo.availMem / 1048576L
                val thresholdMb = (memoryInfo.threshold / 1048576L).coerceAtLeast(1L)
                lastAvailableMb = availableMb

                val tier = when {
                    memoryInfo.lowMemory ||
                        availableMb < (thresholdMb * 12L) / 10L -> Tier.CRITICAL
                    availableMb < (thresholdMb * 18L) / 10L -> Tier.PRESSURE
                    availableMb < (thresholdMb * 25L) / 10L -> Tier.WATCH
                    else -> Tier.HEALTHY
                }
                currentTier = tier.name

                RuntimeLogger.log(
                    "MEMORY POLLED | tier=${tier.name} | available=${availableMb}MB | " +
                        "threshold=${thresholdMb}MB | lowMemory=${memoryInfo.lowMemory}",
                    "MEMORY"
                )

                if (tier == Tier.CRITICAL) {
                    // Real recovery: act on the system's RAM consumers,
                    // not our own heap. Engine enforces its own cooldown.
                    val purged = AggressiveMemoryHoarding.executePurge(this)
                    if (purged) recoveryCount.incrementAndGet()
                }

                publishHealth(
                    "tier=${tier.name} avail=${availableMb}MB purges=${recoveryCount.get()}"
                )

                scheduleTelemetry(tier.pollMs + Random.nextLong(-100L, 100L), epoch)
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                RuntimeLogger.log("MEMORY telemetry failed: ${e.message}", "MEMORY_ERROR")
                publishHealth("telemetry error: ${e.message}")
                scheduleTelemetry(10_000L, epoch)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleNextHeartbeat(delayMs: Long) {
        if (!isRunning) return
        executorService.schedule({
            if (!isRunning) return@schedule
            try {
                publishHealth("heartbeat tier=$currentTier avail=${lastAvailableMb}MB")
                scheduleNextHeartbeat(10_000L + Random.nextLong(-75L, 75L))
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                RuntimeLogger.log("Heartbeat engine exception: ${e.message}", "HEALTH_ERROR")
                scheduleNextHeartbeat(5_000L)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun publishHealth(details: String) {
        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_memory",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = errorCount.get(),
                recoveryCount = recoveryCount.get(),
                details = details
            )
        )
    }

    override fun onDestroy() {
        isRunning = false
        RuntimeLogger.log("MemoryAdapterService shutting down: Canceling Executors", "ADAPTER")
        executorService.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

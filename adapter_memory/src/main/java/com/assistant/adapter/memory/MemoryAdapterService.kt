package com.assistant.adapter.memory

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
 * HIGH-PERFORMANCE MEMORY ADAPTER NODE
 * Engineered for Client-Side Micro-Gesture Frameworks.
 * Optimizes memory polling by offloading all operations from the Main Thread to
 * dedicated I/O Executors, guaranteeing zero frame-drop or input stuttering.
 *
 * OMEGA UPGRADE APPLIED:
 * - Removed kotlinx.coroutines dependency (fixes unresolved references).
 * - Fixed AtomicLong to AtomicInteger type mismatches.
 * - Implemented Server-Tick Sync variance scheduling.
 */
class MemoryAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { true })

    // AMPLIFIED INPUT EFFECTIVENESS: Utilizing dedicated background executor threads
    // to completely free up the Main/UI thread for high-frequency AccessibilityService touch injections.
    private val executorService: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    private lateinit var activityManager: ActivityManager
    
    // Caching MemoryInfo to eliminate object allocation churn and GC overhead on every tick
    private val memoryInfo = ActivityManager.MemoryInfo()

    // Fixed Type Mismatch: AdapterHealthSnapshot expects Int, using AtomicInteger instead of AtomicLong
    private val errorCount = AtomicInteger(0)
    private val recoveryCount = AtomicInteger(0)

    @Volatile
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        RuntimeLogger.log("MemoryAdapterService started: INITIALIZING HIGH-PERFORMANCE NODE", "ADAPTER")

        setupForegroundService()
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_memory",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = errorCount.get(),
                recoveryCount = recoveryCount.get(),
                details = "Foreground service running - Executor Optimized"
            )
        )

        startHeartbeatEngine()
        startMemoryTelemetryEngine()
    }

    private fun setupForegroundService() {
        val channelId = "memory_adapter"
        val channelName = "Memory Core Engine"
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_MIN).apply {
            description = "High-Frequency Memory Polling Node"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Splendor Memory Node")
            .setContentText("Active Memory Stabilization")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        startForeground(9993, notification)
    }

    private fun startHeartbeatEngine() {
        RuntimeLogger.log("MemoryAdapter heartbeat engine started (I/O Thread)", "HEALTH")
        scheduleNextHeartbeat(10000L)
    }

    private fun scheduleNextHeartbeat(delayMs: Long) {
        if (!isRunning) return
        executorService.schedule({
            if (!isRunning) return@schedule
            try {
                AdapterHealthRegistry.update(
                    AdapterHealthSnapshot(
                        adapterName = "adapter_memory",
                        status = "ACTIVE",
                        lastHeartbeat = System.currentTimeMillis(),
                        errorCount = errorCount.get(),
                        recoveryCount = recoveryCount.get(),
                        details = "Heartbeat active - Optimized"
                    )
                )

                // ADAPTIVE NOISE HUMANIZATION & SERVER-TICK SYNC
                // Micro-varianced delays prevent the heartbeat from causing OS scheduler resonance
                // locking, ensuring it doesn't align disruptively with server packet ticks.
                val baseDelay = 10000L
                val variance = Random.nextLong(-75L, 75L)
                scheduleNextHeartbeat(baseDelay + variance)

            } catch (e: Exception) {
                errorCount.incrementAndGet()
                RuntimeLogger.log("Heartbeat engine exception: ${e.message}", "HEALTH_ERROR")
                scheduleNextHeartbeat(5000L) // Staggered backoff on failure
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun startMemoryTelemetryEngine() {
        RuntimeLogger.log("Memory telemetry engine started (I/O Thread)", "MEMORY")
        scheduleNextTelemetry(500L) // Initial quick poll
    }

    private fun scheduleNextTelemetry(delayMs: Long) {
        if (!isRunning) return
        executorService.schedule({
            if (!isRunning) return@schedule
            try {
                // Populate cached memory object to avoid rapid memory allocation (zero-churn)
                activityManager.getMemoryInfo(memoryInfo)

                val availableMb = memoryInfo.availMem / 1048576L // 1024 * 1024
                val thresholdMb = memoryInfo.threshold / 1048576L

                RuntimeLogger.log(
                    "MEMORY POLLED | available=${availableMb}MB | threshold=${thresholdMb}MB | lowMemory=${memoryInfo.lowMemory}",
                    "MEMORY"
                )

                // DYNAMIC POLLING ADAPTATION
                // If memory is dipping towards critical, tighten the polling cycle and trigger background recovery
                val nextDelay = if (memoryInfo.lowMemory || availableMb < thresholdMb * 1.5) {
                    recoveryCount.incrementAndGet()
                    RuntimeLogger.log("MEMORY CRITICAL: Triggering Background GC preemptively", "MEMORY_WARN")

                    // Execute off main-thread GC to clear buffers without blocking gesture inputs
                    System.gc()
                    System.runFinalization()

                    15000L + Random.nextLong(-50L, 50L) // 15s poll when under pressure
                } else {
                    30000L + Random.nextLong(-100L, 100L) // 30s poll when healthy
                }

                scheduleNextTelemetry(nextDelay)
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                RuntimeLogger.log("MEMORY telemetry failed: ${e.message}", "MEMORY_ERROR")
                scheduleNextTelemetry(10000L) // Staggered backoff on failure
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    override fun onDestroy() {
        isRunning = false
        RuntimeLogger.log("MemoryAdapterService shutting down: Canceling Executors", "ADAPTER")
        // Immediately collapse all background tasks to halt memory polling instantly
        executorService.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

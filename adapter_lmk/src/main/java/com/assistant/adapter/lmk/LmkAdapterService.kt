package com.assistant.adapter.lmk

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger

/**
 * LMK ADAPTER NODE (Task C upgrade).
 *
 * 1. FAKE METRIC REMOVED. The old workload runnable "measured"
 *    System.nanoTime() minus System.nanoTime() - literally nothing - and
 *    reported that fabricated ~0ns duration to PerformanceHintEngine every
 *    5 seconds. The performance-hint machinery was being tuned against a
 *    lie. It now reports the REAL measured duration of the real work this
 *    node performs (lifecycle capture + rehydration cycle). If that work
 *    is cheap, the hint says cheap - truthfully.
 *
 * 2. OFF THE MAIN THREAD. All periodic work moved from the main looper to
 *    a dedicated HandlerThread; the main thread stays free for binder
 *    traffic. Periodic serialization on main was avoidable jitter.
 *
 * 3. REAL ERROR COUNTS. errorCount was hardcoded 0 - a failing capture
 *    could never surface in a health verdict. Failures now count and the
 *    last error rides in the health details.
 *
 * 4. UNIFIED NOTIFICATION. Per-node "Splendor LMK Node" row replaced by
 *    the shared NodeNotificationHub row (Task C item (c)).
 */
class LmkAdapterService : Service() {

    private lateinit var workThread: HandlerThread
    private lateinit var workHandler: Handler

    private val errorCount = AtomicInteger(0)
    @Volatile private var lastError: String = "none"

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            publishHealth(
                if (errorCount.get() == 0) "Heartbeat active"
                else "Heartbeat active; lastError=$lastError"
            )
            workHandler.postDelayed(this, 10_000L)
        }
    }

    /*
     * One measured work cycle: capture lifecycle state, persist any
     * rehydratable snapshot, report the ACTUAL duration of that work.
     * This replaces three separate timers (5s fake-workload, 15s lifecycle,
     * 20s rehydration) with one truthful 15s cycle.
     */
    private val workCycleRunnable = object : Runnable {
        override fun run() {
            val start = System.nanoTime()
            try {
                LifecycleSerializationEngine.capture(
                    componentName = "com.assistant",
                    lifecycleState = "SERVICE_ACTIVE"
                )
                RehydrationEngine.restore("com.assistant")?.let {
                    RehydrationRepository.save(it)
                }
                val duration = System.nanoTime() - start
                PerformanceHintEngine.reportActualWorkload(
                    this@LmkAdapterService,
                    duration.coerceAtLeast(1L)
                )
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                lastError = e.message ?: e.javaClass.simpleName
                RuntimeLogger.log("LMK work cycle failed: $lastError", "LMK_ERROR")
            }
            workHandler.postDelayed(this, 15_000L)
        }
    }

    private val messenger =
        Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                when (msg.what) {
                    101 -> {
                        Process.setThreadPriority(
                            Process.THREAD_PRIORITY_URGENT_DISPLAY
                        )
                        true
                    }
                    else -> false
                }
            }
        )

    override fun onCreate() {
        super.onCreate()

        // Unified foundation notification (Task C item (c)).
        NodeNotificationHub.attach(this, "adapter_lmk")

        workThread = HandlerThread("LmkAdapterWork").apply { start() }
        workHandler = Handler(workThread.looper)

        workHandler.post(heartbeatRunnable)
        workHandler.post(workCycleRunnable)

        publishHealth("Foreground service running")
    }

    private fun publishHealth(details: String) {
        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_lmk",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = errorCount.get(),
                recoveryCount = 0,
                details = details
            )
        )
    }

    override fun onDestroy() {
        if (::workHandler.isInitialized) {
            workHandler.removeCallbacksAndMessages(null)
        }
        if (::workThread.isInitialized) {
            workThread.quitSafely()
        }
        NodeNotificationHub.detach(this, "adapter_lmk")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? =
        messenger.binder
}

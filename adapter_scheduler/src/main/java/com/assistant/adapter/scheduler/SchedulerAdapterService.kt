package com.assistant.adapter.scheduler
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.survival.ResourceBudgetRegistry
import com.assistant.diagnostic.AdapterSignalBus

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

class SchedulerAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastFleet = "no sweep yet"

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_scheduler",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "fleet: $lastFleet"
                )
            )
            RuntimeLogger.log("Scheduler heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val schedulerHandler = Handler(Looper.getMainLooper())

    private val schedulerRunnable = object : Runnable {
        override fun run() {

            var active = 0
            var degraded = 0
            var offline = 0

            AdapterHealthRegistry.getAll().forEach { snapshot ->

                when (
                    AdapterHealthRegistry.effectiveStatus(
                        snapshot.adapterName
                    )
                ) {
                    "ACTIVE" -> active++
                    "DEGRADED" -> degraded++
                    "OFFLINE" -> offline++
                }
            }

            ResourceBudgetRegistry.update(active, degraded, offline)
            // PHASE3: publish fleet state to bus so SpeedCompensation and LoadShed can react
            AdapterSignalBus.publishFleet(offline)

            lastFleet = "active=$active degraded=$degraded offline=$offline"
            val fleetWarn = if (offline > 2) " *** FLEET DEGRADED — $offline adapters offline ***" else ""
            RuntimeLogger.log(
                "FLEET HEALTH active=$active degraded=$degraded offline=$offline$fleetWarn",
                "SCHEDULER"
            )

            schedulerHandler.postDelayed(
                this,
                15000L
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("SchedulerAdapterService started", "ADAPTER")

        // Unified foundation notification (Task C item (e)) - this node was
        // the SIXTH service on colliding foreground ID 9993.
        NodeNotificationHub.attach(this, "adapter_scheduler")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_scheduler",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("Scheduler heartbeat scheduler started", "HEALTH")

        schedulerHandler.post(schedulerRunnable)
        RuntimeLogger.log("Scheduler fleet monitor started", "SCHEDULER")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        schedulerHandler.removeCallbacks(schedulerRunnable)
        NodeNotificationHub.detach(this, "adapter_scheduler")
        RuntimeLogger.log("Scheduler heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

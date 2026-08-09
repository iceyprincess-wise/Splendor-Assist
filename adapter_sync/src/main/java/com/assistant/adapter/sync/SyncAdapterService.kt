package com.assistant.adapter.sync
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

class SyncAdapterService : Service() {
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_sync",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))

    override fun onCreate() {
        super.onCreate()

        // Unified foundation notification (Task C item (e)) - replaces the
        // per-node row on ID 9992.
        NodeNotificationHub.attach(this, "adapter_sync")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_sync",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        NodeNotificationHub.detach(this, "adapter_sync")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

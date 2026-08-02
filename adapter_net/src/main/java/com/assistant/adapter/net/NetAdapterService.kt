package com.assistant.adapter.net
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger


class NetAdapterService : Service() {
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_net",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = NetProbeEngine.summary()
                    )
                )
                RuntimeLogger.log("NetAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("NetAdapterService started", "ADAPTER")
        val channel = NotificationChannel("net_adapter", "Net Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "net_adapter")
            .setContentTitle("Splendor Net Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9994, notification)

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_net",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        // ---- NET ENGINE STACK IGNITION ----
        CarrierProfileEngine.detect(this)
        NetworkStateEngine.start(this)
        NetProbeEngine.start(this)
        RadioKeepAliveEngine.start()
        DnsWarmupEngine.start()
        CongestionSentinelEngine.start()
        RuntimeLogger.log("Net engine stack ignited: 6 engines", "NET")

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("NetAdapter heartbeat scheduler started", "HEALTH")
    }


    override fun onDestroy() {
        NetworkStateEngine.stop()
        NetProbeEngine.stop()
        RadioKeepAliveEngine.stop()
        DnsWarmupEngine.stop()
        CongestionSentinelEngine.stop()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        RuntimeLogger.log("NetAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

package com.assistant.adapter.ping
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
import java.net.InetAddress
import java.util.concurrent.Executors

class PingAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_ping",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("PingAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val pingHandler = Handler(Looper.getMainLooper())

    private val pingExecutor =
        Executors.newSingleThreadExecutor()

    private val pingRunnable = object : Runnable {

        override fun run() {

            pingExecutor.execute {

                try {

                    val start =
                        System.currentTimeMillis()

                    InetAddress
                        .getByName("google.com")

                    val latency =
                        System.currentTimeMillis() - start

                    val quality =
                        when {
                            latency < 100 -> "GOOD"
                            latency < 300 -> "FAIR"
                            else -> "POOR"
                        }

                    RuntimeLogger.log(
                        "PING latency=${latency}ms quality=$quality",
                        "PING"
                    )

                } catch (e: Exception) {

                    RuntimeLogger.log(
                        "PING connectivity check failed",
                        "PING"
                    )
                }
            }

            pingHandler.postDelayed(this, 30000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("PingAdapterService started", "ADAPTER")
        val channel = NotificationChannel("ping_adapter", "Ping Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "ping_adapter")
            .setContentTitle("Splendor Ping Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_ping",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("PingAdapter heartbeat scheduler started", "HEALTH")

        pingHandler.post(pingRunnable)
        RuntimeLogger.log("Ping telemetry started", "PING")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        pingHandler.removeCallbacks(pingRunnable)
        pingExecutor.shutdownNow()
        RuntimeLogger.log("PingAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

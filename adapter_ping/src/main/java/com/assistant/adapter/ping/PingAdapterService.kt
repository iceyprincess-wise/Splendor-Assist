package com.assistant.adapter.ping
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger
import java.net.InetAddress
import java.util.concurrent.Executors
import com.assistant.diagnostic.AdapterSignalBus

class PingAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastResolveMs = -1L
    @Volatile private var lastQuality = "UNKNOWN"

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_ping",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "dnsResolve=${lastResolveMs}ms quality=$lastQuality"
                )
            )
            RuntimeLogger.log("PingAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val pingHandler = Handler(Looper.getMainLooper())

    private val pingExecutor =
        Executors.newSingleThreadExecutor()

    /*
     * HONESTY NOTE (Task C): this probe measures DNS RESOLUTION time, not
     * network round-trip - the OS resolver may answer from cache in ~0ms.
     * The real RTT authority is adapter_net's NetProbeEngine; this node's
     * reading is a coarse connectivity indicator only, and its log line and
     * health details now say exactly what it measures instead of calling it
     * "latency".
     */
    private val pingRunnable = object : Runnable {

        override fun run() {

            pingExecutor.execute {

                try {

                    val start =
                        System.currentTimeMillis()

                    InetAddress
                        .getByName("google.com")

                    val resolveMs =
                        System.currentTimeMillis() - start

                    val quality =
                        when {
                            resolveMs < 100 -> "GOOD"
                            resolveMs < 300 -> "FAIR"
                            else -> "POOR"
                        }

                    lastResolveMs = resolveMs
                    lastQuality = quality
                    AdapterSignalBus.publishPing(quality)  // PHASE3: feed RuntimeDecisionLoop

                    RuntimeLogger.log(
                        "PING dnsResolve=${resolveMs}ms quality=$quality (connectivity indicator, not RTT)",
                        "PING"
                    )

                } catch (e: Exception) {

                    lastQuality = "OFFLINE"
                    RuntimeLogger.log(
                        "PING connectivity check failed",
                        "PING"
                    )
                }
            }

            pingHandler.postDelayed(
                this,
                30000L
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("PingAdapterService started", "ADAPTER")

        // Unified foundation notification (Task C item (e)) - this node was
        // the FIFTH service on colliding foreground ID 9993.
        NodeNotificationHub.attach(this, "adapter_ping")

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
        NodeNotificationHub.detach(this, "adapter_ping")
        RuntimeLogger.log("PingAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

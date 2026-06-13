package com.assistant.adapter.lmk

import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Process

class LmkAdapterService : Service() {

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val rehydrationHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_lmk",
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

    private val lifecycleRunnable = object : Runnable {
        override fun run() {
            LifecycleSerializationEngine.capture(
                componentName = "com.assistant",
                lifecycleState = "SERVICE_ACTIVE"
            )
            lifecycleHandler.postDelayed(this, 15000)
        }
    }

    private val rehydrationRunnable = object : Runnable {
        override fun run() {
            RehydrationEngine.restore("com.assistant")?.let {
                RehydrationRepository.save(it)
            }
            rehydrationHandler.postDelayed(this, 20000)
        }
    }

    private val messenger =
        Messenger(
            Handler { msg ->
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

        val channel =
            NotificationChannel(
                "lmk_adapter",
                "LMK Core",
                NotificationManager.IMPORTANCE_MIN
            )

        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)

        val notification =
            Notification.Builder(this, "lmk_adapter")
                .setContentTitle("Splendor LMK Node")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()

        startForeground(9991, notification)

        heartbeatHandler.post(heartbeatRunnable)
        lifecycleHandler.post(lifecycleRunnable)
        rehydrationHandler.post(rehydrationRunnable)

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_lmk",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        lifecycleHandler.removeCallbacks(lifecycleRunnable)
        rehydrationHandler.removeCallbacks(rehydrationRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? =
        messenger.binder
}

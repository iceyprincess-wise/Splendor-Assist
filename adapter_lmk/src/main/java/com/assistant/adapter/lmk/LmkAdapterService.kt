package com.assistant.adapter.lmk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process

class LmkAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg ->
        when (msg.what) {
            101 -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                true
            }
            else -> false
        }
    }))

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("lmk_adapter", "LMK Core", NotificationManager.IMPORTANCE_MIN)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "lmk_adapter")
            .setContentTitle("Splendor LMK Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9991, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

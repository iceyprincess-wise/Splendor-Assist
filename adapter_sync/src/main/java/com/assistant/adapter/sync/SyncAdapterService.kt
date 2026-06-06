package com.assistant.adapter.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.view.Choreographer

class SyncAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("sync_adapter", "Sync Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "sync_adapter")
            .setContentTitle("Splendor Sync Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9992, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

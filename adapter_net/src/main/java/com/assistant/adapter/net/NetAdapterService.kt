package com.assistant.adapter.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Messenger

class NetAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("net_adapter", "Net Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "net_adapter")
            .setContentTitle("Splendor Net Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9994, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

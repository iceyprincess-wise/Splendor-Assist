package com.assistant.adapter.input

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Messenger

class InputAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("input_adapter", "Input Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "input_adapter")
            .setContentTitle("Splendor Input Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}

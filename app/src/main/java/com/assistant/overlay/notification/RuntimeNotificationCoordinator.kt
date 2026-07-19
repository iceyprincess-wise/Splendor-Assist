package com.assistant.overlay.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.assistant.overlay.R

object RuntimeNotificationCoordinator{

    private const val CHANNEL="splendor_runtime"

    fun update(
        context:Context,
        antiban:Boolean,
        matchDetected:Boolean,
        recording:Boolean,
        saved:Boolean
    ){

        val nm=context.getSystemService(
            NotificationManager::class.java
        )

        if(Build.VERSION.SDK_INT>=26){

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Splendor Runtime",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val text=buildString{

            append(if(antiban)"🎶 AntiBan  " else "")
            append(if(matchDetected)"🕶️ Match  " else "")
            append(if(recording)"🎥 Recording  " else "")
            append(if(saved)"💾 Saved" else "")
        }

        val n:Notification=
            NotificationCompat.Builder(context,CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Splendor Assist")
                .setContentText(text.ifBlank{"Engine Active"})
                .setOngoing(true)
                .build()

        nm.notify(7001,n)
    }
}

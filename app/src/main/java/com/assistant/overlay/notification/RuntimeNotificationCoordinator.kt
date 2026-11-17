package com.assistant.overlay.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.assistant.overlay.R

object RuntimeNotificationCoordinator{

    private const val CHANNEL="splendor_runtime"

    // No63-D21: createNotificationChannel() used to run on EVERY update()
    // call — a binder round-trip on every match detection, for a channel
    // that already exists. Create once per process.
    @Volatile
    private var channelCreated = false

    // No63-D19: the `recording` and `saved` parameters are removed.
    // They were dead in practice: no recording/saved state exists
    // anywhere in the repo (no isRecording, clipSaved or savedClip
    // symbol; MediaProjection is capture-only, nothing records to a
    // file), and the sole call site (OverlayService) passed false/false,
    // so the "Recording" / "Saved" text segments could never appear.
    // PROVEN dead and not wireable — removed under the queue-clearing
    // decision, not kept as decoration. If a recording feature is ever
    // added, its real state should feed new parameters here.
    fun update(
        context:Context,
        antiban:Boolean,
        matchDetected:Boolean
    ){

        val nm=context.getSystemService(
            NotificationManager::class.java
        )

        // No63-D20: the SDK_INT >= 26 guard was dead — minSdk is 26 in
        // both app/build.gradle.kts and core/build.gradle.kts, so this
        // branch always ran. The channel is created unconditionally now.
        if (!channelCreated) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Splendor Runtime",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            channelCreated = true
        }

        val text=buildString{

            append(if(antiban)"🎶 AntiBan  " else "")
            append(if(matchDetected)"🕶️ Match  " else "")
        }

        val n:Notification=
            NotificationCompat.Builder(context,CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Splendor Assist")
                .setContentText(text.ifBlank{com.assistant.SplendorCaptureRecovery.statusText("Engine Active") /* SPLD-PATCH-v2:STATUS */})
                .setOngoing(true)
                .build()

        nm.notify(7001,n)
    }
}

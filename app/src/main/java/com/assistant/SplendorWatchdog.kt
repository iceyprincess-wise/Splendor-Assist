package com.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.File

// SPLD-PATCH-v2:WATCHDOG-ENGINE
class SplendorWatchdogService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val r = object : Runnable { override fun run() { tick(); h.postDelayed(this, 60_000) } }
    override fun onCreate() { super.onCreate(); h.post(r) }
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("spld_watch") == null)
            nm.createNotificationChannel(NotificationChannel("spld_watch", "Watchdog", NotificationManager.IMPORTANCE_LOW))
        startForeground(778, Notification.Builder(this, "spld_watch")
            .setContentTitle("Splendor watchdog").setContentText("guarding session")
            .setSmallIcon(android.R.drawable.ic_menu_info_details).setOngoing(true).build())
        return START_STICKY
    }
    private fun tick() {
        try {
            val dir = File("/sdcard/Splendor-Assist")
            val files = dir.listFiles() ?: return
            val ref = files.filter { it.name.contains("heartbeat", true) || it.name.contains("marker", true) }
                .maxByOrNull { it.lastModified() } ?: files.maxByOrNull { it.lastModified() } ?: return
            if (System.currentTimeMillis() - ref.lastModified() > 15 * 60_000L) relaunch()
        } catch (e: Exception) { }
    }
    private fun relaunch() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(it) } catch (e: Exception) { }
        }
        try {
            val am = getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getService(this, 1, Intent(this, SplendorWatchdogService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 5 * 60_000L, pi)
        } catch (e: Exception) { }
    }
    override fun onBind(i: Intent?): IBinder? = null
}

object SplendorWatchdogStart {
    fun start(ctx: android.content.Context) {
        try {
            val i = Intent(ctx, SplendorWatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) { }
    }
}

package com.assistant

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference

// SPLD-PATCH-v2:RECOVERY-ENGINE
object SplendorCaptureRecovery {
    private const val TAG = "SplendorRecovery"
    private const val CH = "splendor_recover"
    private var svcRef: WeakReference<Service>? = null
    @Volatile private var lastFrame = 0L
    @Volatile private var armed = false
    @Volatile private var dead = false
    private val handler = Handler(Looper.getMainLooper())
    private val checker = object : Runnable {
        override fun run() { check(); handler.postDelayed(this, 4000) }
    }

    fun attach(svc: Service) {
        svcRef = WeakReference(svc); ensureChannel(svc)
        handler.removeCallbacks(checker); handler.post(checker)
    }
    fun markFrame() { lastFrame = System.currentTimeMillis(); armed = true }
    fun statusText(default: String): String =
        if (!dead) default else "ENGINE DEAD - tap to re-authorize capture"
    
    fun requestReauth() {
        dead = true
        val svc = svcRef?.get() ?: return
        Log.w(TAG, "capture stopped -> requesting fresh user authorization")
        val i = Intent(svc, SplendorReauthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(svc, 4242, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = Notification.Builder(svc, CH)
            .setContentTitle("Splendor capture dead")
            .setContentText("Tap to re-authorize screen capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi).setOngoing(true)
        svc.getSystemService(NotificationManager::class.java).notify(777, nb.build())
    }

    private fun check() {
        if (!armed || lastFrame == 0L || dead) return
        if (System.currentTimeMillis() - lastFrame > 8000) { dead = true; onRevoked() }
    }
    private fun onRevoked() {
        val svc = svcRef?.get() ?: return
        Log.w(TAG, "capture stale -> requesting fresh user authorization")
        val i = Intent(svc, SplendorReauthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(svc, 4242, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = Notification.Builder(svc, CH)
            .setContentTitle("Splendor capture dead")
            .setContentText("Tap to re-authorize screen capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi).setOngoing(true)
        svc.getSystemService(NotificationManager::class.java).notify(777, nb.build())
    }
    fun deliver(rc: Int, data: Intent) {
        dead = false; armed = false; lastFrame = 0L
        val svc = svcRef?.get() ?: return
        val ms = svc.javaClass.methods
        // SPLD-PATCH-v4:TOKEN-APPLY
        val m = ms.firstOrNull { it.name == "applyFreshProjection" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType && Intent::class.java.isAssignableFrom(it.parameterTypes[1]) }
        if (m != null) {
            try { m.invoke(svc, rc, data); Log.i(TAG, "capture restored via applyFreshProjection") } catch (e: Exception) { Log.e(TAG, "restore failed", e) }
        } else {
            Log.w(TAG, "applyFreshProjection method not resolvable at runtime")
        }
    }
    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Recovery", NotificationManager.IMPORTANCE_HIGH))
    }
}

class SplendorReauthActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        try {
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
        } catch (e: Exception) { finish() }
    }
    override fun onActivityResult(rc: Int, resC: Int, d: Intent?) {
        super.onActivityResult(rc, resC, d)
        if (resC == RESULT_OK && d != null) SplendorCaptureRecovery.deliver(resC, d)
        finish()
    }
}

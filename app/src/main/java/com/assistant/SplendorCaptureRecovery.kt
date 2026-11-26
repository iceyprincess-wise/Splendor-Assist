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
    // CAPTUREFIX-2: deferred reauth. Mid-match, a dead token must NEVER
    // pop a notification or an activity at the user — the match keeps
    // running degraded and reauth waits for the next non-match moment.
    // deferReauth() arms the flag; the 4 s checker flushes it as soon
    // as the game is not foreground.
    @Volatile private var reauthDeferred = false

    fun deferReauth() {
        reauthDeferred = true
        Log.w(TAG, "capture reauth deferred to the next non-match moment (no popup mid-match)")
    }
    private val handler = Handler(Looper.getMainLooper())
    private val checker = object : Runnable {
        override fun run() { check(); handler.postDelayed(this, 4000) }
    }

    fun attach(svc: Service) {
        svcRef = WeakReference(svc); ensureChannel(svc)
        handler.removeCallbacks(checker); handler.post(checker)
    }
    // No68-C-D24: staleness used to be measured on System.currentTimeMillis
    // (wall clock) — a clock jump could declare capture dead or hide a
    // real stall. Use elapsedRealtime, the monotonic clock, like the rest
    // of the vision path.
    fun markFrame() { lastFrame = android.os.SystemClock.elapsedRealtime(); armed = true; dead = false }
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
        // CAPTUREFIX-2: flush a deferred reauth as soon as we are NOT
        // mid-match — even if capture never armed (first launch / sticky
        // restart). The flush runs the full auto-heal (token reuse
        // first); only if that fails does the tap-to-reauthorize
        // notification post. While the game is foreground the flag stays
        // armed and nothing user-facing fires.
        if (reauthDeferred) {
            val inGame = try { com.assistant.VisionTrust.isGameForeground() } catch (_: Throwable) { false }
            if (!inGame) {
                reauthDeferred = false
                val healed = try { com.assistant.OverlayService.requestAutoHeal() } catch (_: Throwable) { false }
                if (!healed) {
                    try { requestReauth() } catch (_: Throwable) {}
                }
            }
        }
        if (!armed || lastFrame == 0L || dead) return
        if (android.os.SystemClock.elapsedRealtime() - lastFrame > 30000) { dead = true; onRevoked() }
    }
    private fun onRevoked() {
        if (svcRef?.get() == null) return
        Log.w(TAG, "capture stale -> auto-healing capture (no manual tap required)")
        // SPLENDOR_V42_AUTOHEAL_REVOKED_BEGIN
        // CAPTUREFIX-2: silent auto-heal only. The old code called the
        // full requestAutoHeal() (which launches the reauth activity
        // when the token is dead) and then posted the tap-to-reauthorize
        // notification — both user-facing, both mid-match. Now: token
        // reuse only; a dead token defers (no notification, no
        // activity) unless the game is already background, and COOLDOWN
        // (throttled, try again in 15 s) is not treated as dead.
        // No68-C-D23: requestAutoHeal()'s Boolean used to be ignored and
        // requestReauth() had zero callers, so when auto-heal failed
        // (e.g. the 15 s anti-spam cooldown) the user got no prompt at
        // all. Fall back to the tap-to-reauthorize notification then. The
        // overlap with auto-heal's own reauth-activity launch is deliberate:
        // the notification persists if that launch is swallowed.
        val silent = try {
            com.assistant.OverlayService.requestAutoHealSilent()
        } catch (_: Throwable) { "EXCEPTION" }
        val healed = silent == "OK"
        if (!healed && silent != "COOLDOWN") {
            val inGame = try { com.assistant.VisionTrust.isGameForeground() } catch (_: Throwable) { false }
            if (inGame) {
                try { deferReauth() } catch (_: Throwable) {}
            } else {
                try { requestReauth() } catch (_: Throwable) {}
            }
        }
        // SPLENDOR_V42_AUTOHEAL_REVOKED_END
    }
    fun deliver(rc: Int, data: Intent) {
        dead = false; armed = false; lastFrame = 0L
        val svc = OverlayService.instance
        if (svc != null) {
            try {
                svc.applyFreshProjection(rc, data)
                Log.i(TAG, "capture restored via applyFreshProjection")
            } catch (e: Exception) {
                Log.e(TAG, "restore failed", e)
            }
        } else {
            Log.w(TAG, "OverlayService instance not available for token restore")
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

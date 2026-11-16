package com.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * F37-D10: receiver for the capture-recovery prompt tap.
 * OverlayService.requestFreshProjectionAuthorization() broadcasts
 * com.assistant.REQUEST_PROJECTION, but no receiver existed repo-wide
 * (manifest: zero receivers), so the tap was dead and the log line
 * claiming MainActivity had been contacted was false. This receiver
 * launches MainActivity with REQUEST_MEDIA_PROJECTION_RECOVERY, the
 * existing onResume entry point that re-runs the capture-permission
 * flow and restarts OverlayService with a fresh token — it works even
 * when the service is dead, unlike the re-auth activity's deliver()
 * path, which needs a live service instance.
 */
class ProjectionRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "com.assistant.REQUEST_PROJECTION") return
        try {
            val i = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("REQUEST_MEDIA_PROJECTION_RECOVERY", true)
            context.startActivity(i)
        } catch (_: Throwable) {
        }
    }
}

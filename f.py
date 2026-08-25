#!/usr/bin/env python3
import os, sys

BASE = "/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant"
OV = os.path.join(BASE, "OverlayService.kt")
SH = os.path.join(BASE, "adapter/smartassist/RuntimeSelfHealEngine.kt")

def load(p):
    with open(p, 'rb') as f: raw = f.read()
    return raw, raw.decode('utf-8').replace('\r\n', '\n')

def save(p, raw, text):
    out = text.replace('\n', '\r\n') if b'\r\n' in raw else text
    with open(p, 'wb') as f: f.write(out.encode('utf-8'))

def rep(text, old, new, tag):
    c = text.count(old)
    if c == 0:
        if new in text: return text, True
        print(f"UNVERIFIED - anchor missing: {tag}; NO change applied."); sys.exit(1)
    if c > 1:
        print(f"BLOCKED - ambiguous anchor ({c}x): {tag}; NO change applied."); sys.exit(1)
    print(f"PROVEN - applied: {tag}")
    return text.replace(old, new, 1), True

def main():
    print("=== SPLDOR-ASSIST CAPTURE-RECOVERY PATCH (V4) ===")
    rawO, ov = load(OV)
    rawS, sh = load(SH)

    if "showCaptureRecoveryPrompt" not in ov:
        ov, _ = rep(ov,
"""    @Volatile
    private var projectionRevoked = false""",
"""    @Volatile
    private var projectionRevoked = false
    @Volatile
    private var recoveryPromptShown = false
    private var recoveryPromptView: TextView? = null""", "OV-fields")

        ov, _ = rep(ov,
"""        @JvmStatic
        fun restartCaptureIfAlive(): Boolean =
            instance?.restartCapture() ?: false
    }""",
"""        @JvmStatic
        fun restartCaptureIfAlive(): Boolean =
            instance?.restartCapture() ?: false

        @JvmStatic
        fun projectionRevoked(): Boolean =
            instance?.projectionRevoked ?: true

        @JvmStatic
        fun requestRecoveryPrompt() {
            instance?.showCaptureRecoveryPrompt()
        }
    }""", "OV-companion")

        ov, _ = rep(ov,
"""                    RuntimeLogger.log("MediaProjection.onStop(): projection revoked; capture resources invalidated; fresh authorization required", "OVERLAY")
                    requestFreshProjectionAuthorization()""",
"""                    RuntimeLogger.log("MediaProjection.onStop(): projection revoked; capture resources invalidated; fresh authorization required", "OVERLAY")
                    requestFreshProjectionAuthorization()
                    showCaptureRecoveryPrompt()""", "OV-onStop")

        ov, _ = rep(ov,
"""        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionRevoked = false""",
"""        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionRevoked = false
        dismissCaptureRecoveryPrompt()""", "OV-setup-dismiss")

        ov, _ = rep(ov,
"""        } catch (t: Throwable) {
            RuntimeLogger.log("MediaProjection recovery launch failed: ${t.javaClass.simpleName}: ${t.message}", "AGENT")
        }
    }""",
"""        } catch (t: Throwable) {
            RuntimeLogger.log("MediaProjection recovery launch failed: ${t.javaClass.simpleName}: ${t.message}", "AGENT")
        }
    }

    /*
     * ROOT-CAUSE FIX (HealLog 2026-08-25): background startActivity is blocked by
     * Android 10+/HyperOS BAL rules, so a revoked projection never recovered.
     * The service already owns an overlay window: show a TOUCHABLE banner plus a
     * high-priority notification. The user tap is the BAL exemption that legally
     * relaunches authorization; fresh token resumes capture via onStartCommand.
     */
    fun showCaptureRecoveryPrompt() {
        Handler(Looper.getMainLooper()).post {
            try {
                if (recoveryPromptShown) return@post
                recoveryPromptShown = true
                val prompt = TextView(this).apply {
                    text = "⚠️ CAPTURE STOPPED - TAP TO RESTORE"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(230, 180, 30, 30))
                    textSize = 14f
                    setPadding(24, 18, 24, 18)
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        dismissCaptureRecoveryPrompt()
                        requestFreshProjectionAuthorization()
                    }
                }
                recoveryPromptView = prompt
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = 120
                windowManager.addView(prompt, params)
                postRecoveryNotification()
                RuntimeLogger.log("CAPTURE RECOVERY PROMPT shown (user tap restores authorization)", "AGENT")
            } catch (t: Throwable) {
                recoveryPromptShown = false
                RuntimeLogger.log("CAPTURE RECOVERY PROMPT failed: ${t.javaClass.simpleName}: ${t.message}", "AGENT")
            }
        }
    }

    private fun dismissCaptureRecoveryPrompt() {
        Handler(Looper.getMainLooper()).post {
            recoveryPromptView?.let { v ->
                try { windowManager.removeViewImmediate(v) } catch (_: Throwable) {}
            }
            recoveryPromptView = null
            recoveryPromptShown = false
        }
    }

    private fun postRecoveryNotification() {
        try {
            val tapIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("REQUEST_MEDIA_PROJECTION_RECOVERY", true)
            }
            val pending = android.app.PendingIntent.getActivity(
                this, 1101, tapIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Splendor Assist: capture stopped")
                .setContentText("Tap to restore screen capture")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(1102, notification)
        } catch (t: Throwable) {
            RuntimeLogger.log("CAPTURE RECOVERY NOTIFICATION failed: ${t.javaClass.simpleName}", "AGENT")
        }
    }""", "OV-recovery-methods")
        save(OV, rawO, ov)
    else:
        print("PROVEN - OverlayService.kt already patched (idempotent skip).")

    if "OverlayService.projectionRevoked()" not in sh:
        sh, _ = rep(sh,
"""            // Capture IS stale
            captureStaleMs = staleMs""",
"""            // Capture IS stale
            captureStaleMs = staleMs

            // ROOT-CAUSE FIX (HealLog 2026-08-25): when the projection is revoked,
            // restartCaptureIfAlive() can NEVER succeed (first branch returns false).
            // Do not burn the 3-attempt budget on a no-op; escalate to the
            // user-visible recovery prompt (tap = BAL exemption = fresh token).
            if (com.assistant.OverlayService.projectionRevoked()) {
                if (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L) {
                    lastRestartAttemptMs = now
                    try { com.assistant.OverlayService.requestRecoveryPrompt() } catch (_: Throwable) {}
                    if (shouldLog("CAPTURE_REVOKED", "revoked")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_REVOKED",
                            detected = "MediaProjection revoked; restartCapture is a no-op by design. Escalated to recovery prompt + notification.",
                            fix = "User tap relaunches authorization; capture resumes on fresh token.",
                            severity = "CRITICAL"
                        ))
                    }
                }
                return
            }""", "SH-revoked-escalation")

        sh, _ = rep(sh,
"""} else if (shouldLog("CAPTURE_STALE", "stale=${staleMs / 1000}s")) {""",
"""} else if (shouldLog("CAPTURE_STALE", "stale-fixed")) {""", "SH-spam-dedup")
        save(SH, rawS, sh)
    else:
        print("PROVEN - RuntimeSelfHealEngine.kt already patched (idempotent skip).")

    print("=== V4 PATCH COMPLETE - run: ./gradlew :app:compileDebugKotlin ===")

if __name__ == "__main__":
    main()

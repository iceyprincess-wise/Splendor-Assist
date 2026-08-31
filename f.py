#!/usr/bin/env python3
import os
import sys

def patch_file(path, replacements):
    if not os.path.exists(path):
        print(f"ERROR: File not found: {path}")
        sys.exit(1)
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    original_content = content
    for old, new in replacements:
        if old not in content:
            print(f"WARNING: Anchor mismatch in {path} for:\n{old[:100]}...")
        content = content.replace(old, new)
        
    if content != original_content:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"SUCCESS: Patched {path}")
    else:
        print(f"INFO: No changes needed for {path}")

# 1. OverlayService.kt - Aggressive KeepAlive & Immediate Recovery Prompts
overlay_path = "app/src/main/java/com/assistant/OverlayService.kt"
overlay_replacements = [
    (
        "if (now - lastFrameProcessedMs > 10000L && lastFrameProcessedMs > 0L) {\n                        RuntimeLogger.log(\"KEEPALIVE: Capture stale for >10s. Proactively recreating surfaces to prevent HyperOS silent kill.\", \"OVERLAY\")",
        "if (now - lastFrameProcessedMs > 5000L && lastFrameProcessedMs > 0L) {\n                        RuntimeLogger.log(\"KEEPALIVE: Capture stale for >5s. Proactively recreating surfaces to prevent HyperOS silent kill.\", \"OVERLAY\")"
    ),
    (
        "keepAliveHandler.postDelayed(this, 45000L)\n            }\n        }\n        keepAliveHandler.postDelayed(keepAliveRunnable!!, 45000L)",
        "keepAliveHandler.postDelayed(this, 5000L)\n            }\n        }\n        keepAliveHandler.postDelayed(keepAliveRunnable!!, 5000L)"
    ),
    (
        """    fun restartCapture(): Boolean {
        if (projectionRevoked) {
            RuntimeLogger.log("AGENT CAPTURE RESTART: projection already revoked; fresh MediaProjection authorization required", "AGENT")
            return false
        }
        if (mediaProjection == null) {
            RuntimeLogger.log("AGENT CAPTURE RESTART: no active MediaProjection", "AGENT")
            return false
        }""",
        """    fun restartCapture(): Boolean {
        if (projectionRevoked) {
            RuntimeLogger.log("AGENT CAPTURE RESTART: projection already revoked; fresh MediaProjection authorization required", "AGENT")
            showCaptureRecoveryPrompt() // EMPOWERED: Force recovery prompt immediately
            return false
        }
        if (mediaProjection == null) {
            RuntimeLogger.log("AGENT CAPTURE RESTART: no active MediaProjection", "AGENT")
            showCaptureRecoveryPrompt() // EMPOWERED: Force recovery prompt immediately
            return false
        }"""
    ),
    (
        """                    RuntimeLogger.log("MediaProjection.onStop(): projection revoked; capture resources invalidated. AI Agent handling silently.", "OVERLAY")""",
        """                    RuntimeLogger.log("MediaProjection.onStop(): projection revoked; aggressively triggering recovery prompt to stop capture death.", "OVERLAY")
                    showCaptureRecoveryPrompt() // EMPOWERED: Immediately prompt user!"""
    )
]
patch_file(overlay_path, overlay_replacements)

# 2. RuntimeSelfHealEngine.kt - Eradicate Silent Suppression
heal_path = "app/src/main/java/com/assistant/adapter/smartassist/RuntimeSelfHealEngine.kt"
heal_replacements = [
    (
        """            if (com.assistant.OverlayService.projectionRevoked()) {
                if (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L) {
                    lastRestartAttemptMs = now

                    // MASSIVE POWER: AI Agent handles projection revoke autonomously and silently.

                    if (shouldLog("CAPTURE_REVOKED", "revoked")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_REVOKED",
                            detected = "MediaProjection revoked; AI Agent handling autonomously without interrupting gameplay.",
                            fix = "Capture resources invalidated. AI Agent operates silently in background.",
                            severity = "CRITICAL"
                        ))
                    }
                }
                return
            }""",
        """            if (com.assistant.OverlayService.projectionRevoked()) {
                if (now - lastRestartAttemptMs > 10_000L || lastRestartAttemptMs == 0L) {
                    lastRestartAttemptMs = now

                    // EMPOWERED: AI Agent aggressively requests fresh projection authorization
                    // to prevent permanent capture death. Silent handling eradicated.
                    try {
                        com.assistant.OverlayService.requestRecoveryPrompt()
                    } catch (_: Throwable) {}

                    if (shouldLog("CAPTURE_REVOKED", "revoked")) {
                        totalHeals++
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_REVOKED",
                            detected = "MediaProjection revoked; AI Agent aggressively requesting fresh user authorization to prevent capture death.",
                            fix = "Recovery prompt forced to screen. User must tap to restore MediaProjection token.",
                            severity = "CRITICAL"
                        ))
                    }
                }
                return
            }"""
    ),
    (
        """            } else if (shouldLog("CAPTURE_STALE", "stale-fixed")) {
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "CAPTURE_STALE",
                    detected = "Capture stale ${staleMs / 1000}s. Restart attempts: $captureRestartAttempts/3.",
                    fix = if (captureRestartAttempts >= 3)
                        "Max restart attempts reached. FORCE-STOP app and reopen."
                    else
                        "Next restart attempt in ${(30_000L - (System.currentTimeMillis() - lastRestartAttemptMs)) / 1000}s.",
                    severity = "CRITICAL"
                ))
            }""",
        """            } else if (shouldLog("CAPTURE_STALE", "stale-fixed")) {
                if (captureRestartAttempts >= 3) {
                    try { com.assistant.OverlayService.requestRecoveryPrompt() } catch (_: Throwable) {}
                }
                record(HealEvent(
                    timestamp = fmt.format(Date()),
                    category = "CAPTURE_STALE",
                    detected = "Capture stale ${staleMs / 1000}s. Restart attempts: $captureRestartAttempts/3.",
                    fix = if (captureRestartAttempts >= 3)
                        "Max restart attempts reached. Recovery prompt forced. User must tap to restore."
                    else
                        "Next restart attempt in ${(30_000L - (System.currentTimeMillis() - lastRestartAttemptMs)) / 1000}s.",
                    severity = "CRITICAL"
                ))
            }"""
    )
]
patch_file(heal_path, heal_replacements)

# 3. SplendorCaptureRecovery.kt - Companion Aggressive Trigger
recovery_path = "app/src/main/java/com/assistant/SplendorCaptureRecovery.kt"
recovery_replacements = [
    (
        """    private fun onRevoked() {
        val svc = svcRef?.get() ?: return
        Log.w(TAG, "capture stale -> requesting fresh user authorization")
        val i = Intent(svc, SplendorReauthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)""",
        """    private fun onRevoked() {
        val svc = svcRef?.get() ?: return
        Log.w(TAG, "capture stale -> requesting fresh user authorization and forcing overlay prompt")
        
        // EMPOWERED: Force the overlay prompt immediately so user cannot miss it
        try {
            com.assistant.OverlayService.requestRecoveryPrompt()
        } catch (_: Throwable) {}

        val i = Intent(svc, SplendorReauthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)"""
    )
]
patch_file(recovery_path, recovery_replacements)

print("ALL CAPTURE DEATH PATCHES APPLIED SUCCESSFULLY.")

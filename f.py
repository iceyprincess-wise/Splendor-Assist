#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SPLENDOR-ASSIST V42.1 HEAL PATCH
================================
Root causes traced from field logs (2026-10-02):

  Session 1 (10:37-11:16): capture=288x640 OK -> COLLECT_STALL @668 ->
      LOOP_FROZEN @12107 (ImageReader stopped, HyperOS projection revoke) ->
      SILENT KILL after 1592s. Heal agent logged CAPTURE_REVOKED but the
      recovery path was BROKEN (broadcast had no receiver, reauth activity
      was not in the manifest) so the projection could never be restored.

  Session 2 (15:47): capture=0x0 source=0x0 -> REGISTRY_EMPTY ->
      COLLECT_ZERO spam for 7+ minutes -> EARLY DEATH 0s after start.
      windowManager.currentWindowMetrics.bounds returned 0x0, so the
      ImageReader was created with 0x0 dimensions and never delivered a
      frame. FrameAssembler.current() stayed null, so checkCaptureThread()
      returned early (`?: return`) and the heal agent could NEVER restart
      capture -> COLLECT_ZERO could never heal.

FIXES
=====
  A1. OverlayService: `val finalWidth/finalHeight` -> `var` (fixes the
      V42 build failure "Val cannot be reassigned" at lines 685-686).
  A2. OverlayService.recreateCaptureSurfacesInternal(): guard 0x0 window
      metrics; fall back to defaultDisplay.getRealMetrics() so the
      ImageReader is never created with 0x0 dimensions.
  B.  RuntimeSelfHealEngine.checkCaptureThread(): handle the null-frame case
      (no frame ever assembled) instead of returning early, so the heal
      agent can actually attempt a capture restart when COLLECT_ZERO fires.
  C.  RuntimeSelfHealEngine.checkCaptureThread(): REVOKED -> auto-heal
      (replaces the broken tap-to-restore prompt).
  D.  OverlayService AUTO-HEAL engine: keeps the granted token; on revoke
      tries token reuse (ZERO user interaction) first, then auto-launches
      the system consent dialog (one tap — Android 14 requires one consent
      per capture session; no app can bypass that). Also adds
      FLAG_KEEP_SCREEN_ON so the screen never turns off mid-capture.
  E.  SplendorCaptureRecovery: staleness threshold 8s -> 30s (kills false
      positives that spammed the prompt), auto-heal on revoke, re-arm on
      frame resume.
  F.  Android 14 service-first ordering: the mediaProjection-type foreground
      service is started BEFORE createScreenCaptureIntent() (targetSdk 34
      requirement) so the projection is not revoked when the app goes to
      background — this is the main reason HyperOS kept killing capture.
  G.  Manifest: declare SplendorReauthActivity (was missing -> the old
      recovery flow could never start it).

Usage:  python3 f.py   (run from repo root or anywhere; script locates repo)
Then:   ./gradlew assembleRelease   (or assembleDebug)
"""
import os
import sys

REPO_DIR = None
for cand in (
    os.getcwd(),
    os.path.expanduser("~/Splendor-Assist"),
    "/data/data/com.termux/files/home/Splendor-Assist",
    "/data/data/com.termux/files/home/projects/Splendor-Assist",
    "/home/ubuntu/Splendor-Assist",
):
    if os.path.isdir(os.path.join(cand, "app", "src", "main", "java")):
        REPO_DIR = cand
        break

if REPO_DIR is None:
    print("[FATAL] Could not locate Splendor-Assist repo. Run this script from the repo root.")
    sys.exit(1)

print("=" * 70)
print("SPLENDOR-ASSIST V42.1 HEAL PATCH")
print("Repo: %s" % REPO_DIR)
print("=" * 70)

OVERLAY = os.path.join(REPO_DIR, "app", "src", "main", "java", "com", "assistant", "OverlayService.kt")
ENGINE = os.path.join(REPO_DIR, "app", "src", "main", "java", "com", "assistant", "gameplay_engine.kt")
RECOVERY = os.path.join(REPO_DIR, "app", "src", "main", "java", "com", "assistant", "SplendorCaptureRecovery.kt")
MAINACT = os.path.join(REPO_DIR, "app", "src", "main", "java", "com", "assistant", "MainActivity.kt")
MANIFEST = os.path.join(REPO_DIR, "app", "src", "main", "AndroidManifest.xml")

results = []


def apply_fix(name, path, old, new, count=1, marker=None):
    """Replace `old` with `new` in `path`. Verifies the replacement landed.
    Idempotent: skips if `marker` (or the new text itself) is already present."""
    if not os.path.isfile(path):
        results.append((name, "FAIL", "file not found: %s" % path))
        return
    with open(path, "r", encoding="utf-8") as fh:
        src = fh.read()
    if marker is not None and marker in src:
        results.append((name, "SKIP", "already applied"))
        return
    if marker is None and new in src:
        results.append((name, "SKIP", "already applied"))
        return
    occurrences = src.count(old)
    if occurrences == 0:
        results.append((name, "FAIL", "anchor not found (file drifted?)"))
        return
    if occurrences != count:
        results.append((name, "FAIL", "anchor found %d times, expected %d" % (occurrences, count)))
        return
    src = src.replace(old, new, 1)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(src)
    with open(path, "r", encoding="utf-8") as fh:
        check = fh.read()
    if new in check:
        results.append((name, "OK", "applied + verified"))
    else:
        results.append((name, "FAIL", "write did not land"))


# =====================================================================
# FIX A1 — OverlayService.kt: val -> var (V42 build failure)
# =====================================================================
A1_OLD = """        val finalWidth: Int
        val finalHeight: Int
"""
A1_NEW = """        var finalWidth: Int
        var finalHeight: Int
"""
apply_fix("A1. OverlayService val->var (compile fix)", OVERLAY, A1_OLD, A1_NEW)

# =====================================================================
# FIX A2 — OverlayService.kt: 0x0 capture guard
# =====================================================================
A2_OLD = """        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            finalWidth = (bounds.width() * scale).toInt() and 0xFFFFFFFE.toInt()
            finalHeight = (bounds.height() * scale).toInt() and 0xFFFFFFFE.toInt()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            finalWidth = (metrics.widthPixels * scale).toInt() and 0xFFFFFFFE.toInt()
            finalHeight = (metrics.heightPixels * scale).toInt() and 0xFFFFFFFE.toInt()
        }
"""

A2_NEW = """        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            finalWidth = (bounds.width() * scale).toInt() and 0xFFFFFFFE.toInt()
            finalHeight = (bounds.height() * scale).toInt() and 0xFFFFFFFE.toInt()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            finalWidth = (metrics.widthPixels * scale).toInt() and 0xFFFFFFFE.toInt()
            finalHeight = (metrics.heightPixels * scale).toInt() and 0xFFFFFFFE.toInt()
        }

        // SPLENDOR_V42_ZERO_CAPTURE_GUARD_BEGIN
        // Field log 2026-10-02 15:47: capture=0x0 source=0x0 -> ImageReader
        // created with 0x0 dimensions -> no frame ever delivered -> COLLECT_ZERO
        // spam + EARLY DEATH. windowManager.currentWindowMetrics.bounds can
        // return 0x0 when the service starts before the display is ready.
        if (finalWidth <= 0 || finalHeight <= 0) {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            finalWidth = (metrics.widthPixels * scale).toInt() and 0xFFFFFFFE.toInt()
            finalHeight = (metrics.heightPixels * scale).toInt() and 0xFFFFFFFE.toInt()
            RuntimeLogger.log("ZERO_CAPTURE_GUARD: window metrics 0x0, fell back to display " + finalWidth + "x" + finalHeight, "OVERLAY")
        }
        if (finalWidth <= 0 || finalHeight <= 0) {
            // Last resort: fixed 288x640 (0.4x of 720x1600) so capture can never be 0x0.
            finalWidth = 288
            finalHeight = 640
            RuntimeLogger.log("ZERO_CAPTURE_GUARD: display metrics also 0x0, forced 288x640", "OVERLAY")
        }
        // SPLENDOR_V42_ZERO_CAPTURE_GUARD_END
"""
apply_fix("A2. OverlayService 0x0 capture guard", OVERLAY, A2_OLD, A2_NEW, marker="SPLENDOR_V42_ZERO_CAPTURE_GUARD_BEGIN")

# =====================================================================
# FIX B — gameplay_engine.kt: checkCaptureThread() null-frame handling
# =====================================================================
B_OLD = """    private fun checkCaptureThread() {
        try {
            val f = FrameAssembler.current() ?: return
            val now = System.currentTimeMillis()
"""

B_NEW = """    private fun checkCaptureThread() {
        try {
            val f = FrameAssembler.current()
            val now = System.currentTimeMillis()

            // SPLENDOR_V42_NULL_FRAME_FIX_BEGIN
            // Field log 2026-10-02 15:47: capture=0x0 -> FrameAssembler.current()
            // stays null -> old `?: return` made COLLECT_ZERO unhealable.
            if (f == null) {
                val state = com.assistant.OverlayService.captureState()
                if (state == com.assistant.OverlayService.CaptureState.ACTIVE ||
                    state == com.assistant.OverlayService.CaptureState.AUTHORIZED) {
                    val canRetry = captureRestartAttempts < 3 &&
                        (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L)
                    if (canRetry) {
                        captureRestartAttempts++
                        lastRestartAttemptMs = now
                        totalHeals++
                        val restarted = try {
                            val cls = Class.forName("com.assistant.OverlayService")
                            val method = cls.getDeclaredMethod("restartCaptureIfAlive")
                            (method.invoke(null) as? Boolean) ?: false
                        } catch (e: Throwable) {
                            RuntimeLogger.log("AGENT: restartCaptureIfAlive failed: ${e.message}", "AGENT")
                            false
                        }
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_RESTART",
                            detected = "No frame ever assembled (capture=" + com.assistant.vision.CameraProfile.diagnostics() + "). " +
                                "Attempt #$captureRestartAttempts of 3.",
                            fix = if (restarted) "RESTART SENT to OverlayService.restartCapture()." else
                                "CAPTURE RESTART FAILED — capture state " + com.assistant.OverlayService.captureState() + ".",
                            severity = if (restarted) "FIXED" else "CRITICAL"
                        ))
                    }
                }
                return
            }
            // SPLENDOR_V42_NULL_FRAME_FIX_END
"""
apply_fix("B. checkCaptureThread null-frame heal", ENGINE, B_OLD, B_NEW, marker="SPLENDOR_V42_NULL_FRAME_FIX_BEGIN")

# =====================================================================
# FIX C — gameplay_engine.kt: REVOKED -> auto-heal (replaces tap prompt)
# =====================================================================
C_PROMPT_OLD = """                    // SPLENDOR_V42_REVOKE_PROMPT_FIX_BEGIN
                    // Field log 2026-10-02 11:16: SILENT KILL after 1592s.
                    // A revoked MediaProjection can ONLY be restored with a fresh
                    // user authorization. Logging silently left the app dead.
                    // Surface the tap-to-restore prompt so recovery is possible.
                    try { com.assistant.OverlayService.requestRecoveryPrompt() } catch (_: Throwable) {}
                    // SPLENDOR_V42_REVOKE_PROMPT_FIX_END
"""
C_PROMPT_NEW = """                    // SPLENDOR_V42_AUTOHEAL_FIX_BEGIN
                    // v2 (2026-10-02): automatic recovery — no manual tap.
                    // 1) token reuse (zero interaction) if the projection session
                    //    is still alive; 2) otherwise auto-launch the system
                    //    consent dialog (one tap — Android 14 requires one
                    //    consent per capture session; no app can bypass it).
                    try { com.assistant.OverlayService.autoHealCapture() } catch (_: Throwable) {}
                    // SPLENDOR_V42_AUTOHEAL_FIX_END
"""
C_EVENT_OLD = """                            detected = "MediaProjection revoked; recovery prompt surfaced for fresh authorization.",
                            fix = "User tap on prompt restores capture with a fresh MediaProjection token.",
"""
C_EVENT_NEW = """                            detected = "MediaProjection revoked; auto-heal triggered (token reuse or system consent).",
                            fix = "Auto-heal: token reuse if session alive, else system consent dialog (single tap).",
"""
C_FRESH_OLD = """            if (state == com.assistant.OverlayService.CaptureState.REVOKED) {
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
            } else if (state == com.assistant.OverlayService.CaptureState.FAILED) {
"""
C_FRESH_NEW = """            if (state == com.assistant.OverlayService.CaptureState.REVOKED) {
                if (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L) {
                    lastRestartAttemptMs = now
                    // SPLENDOR_V42_AUTOHEAL_FIX_BEGIN
                    // v2 (2026-10-02): automatic recovery — no manual tap.
                    // 1) token reuse (zero interaction) if the projection session
                    //    is still alive; 2) otherwise auto-launch the system
                    //    consent dialog (one tap — Android 14 requires one
                    //    consent per capture session; no app can bypass it).
                    try { com.assistant.OverlayService.autoHealCapture() } catch (_: Throwable) {}
                    // SPLENDOR_V42_AUTOHEAL_FIX_END
                    if (shouldLog("CAPTURE_REVOKED", "revoked")) {
                        record(HealEvent(
                            timestamp = fmt.format(Date()),
                            category = "CAPTURE_REVOKED",
                            detected = "MediaProjection revoked; auto-heal triggered (token reuse or system consent).",
                            fix = "Auto-heal: token reuse if session alive, else system consent dialog (single tap).",
                            severity = "CRITICAL"
                        ))
                    }
                }
                return
            } else if (state == com.assistant.OverlayService.CaptureState.FAILED) {
"""

with open(ENGINE, "r", encoding="utf-8") as fh:
    _engine_src = fh.read()
if "SPLENDOR_V42_AUTOHEAL_FIX_BEGIN" in _engine_src:
    results.append(("C. REVOKED -> auto-heal", "SKIP", "already applied"))
elif "SPLENDOR_V42_REVOKE_PROMPT_FIX_BEGIN" in _engine_src:
    if C_PROMPT_OLD in _engine_src:
        _engine_src = _engine_src.replace(C_PROMPT_OLD, C_PROMPT_NEW, 1)
        if C_EVENT_OLD in _engine_src:
            _engine_src = _engine_src.replace(C_EVENT_OLD, C_EVENT_NEW, 1)
        with open(ENGINE, "w", encoding="utf-8") as fh:
            fh.write(_engine_src)
        with open(ENGINE, "r", encoding="utf-8") as fh:
            results.append(("C. REVOKED -> auto-heal", "OK" if "SPLENDOR_V42_AUTOHEAL_FIX_BEGIN" in fh.read() else "FAIL", "prompt block replaced"))
    else:
        results.append(("C. REVOKED -> auto-heal", "FAIL", "prompt block anchor not found"))
elif C_FRESH_OLD in _engine_src:
    _engine_src = _engine_src.replace(C_FRESH_OLD, C_FRESH_NEW, 1)
    with open(ENGINE, "w", encoding="utf-8") as fh:
        fh.write(_engine_src)
    with open(ENGINE, "r", encoding="utf-8") as fh:
        results.append(("C. REVOKED -> auto-heal", "OK" if "SPLENDOR_V42_AUTOHEAL_FIX_BEGIN" in fh.read() else "FAIL", "fresh block applied"))
else:
    results.append(("C. REVOKED -> auto-heal", "FAIL", "no matching anchor found"))

# =====================================================================
# FIX D — OverlayService.kt: AUTO-HEAL engine
# =====================================================================
# D1: fields
D1_OLD = """    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
"""
D1_NEW = """    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    // SPLENDOR_V42_AUTOHEAL_FIELDS_BEGIN
    // Auto-heal capture: keep the granted token so recovery can be attempted
    // without user interaction (token reuse) before falling back to the
    // system consent dialog (one tap — Android 14 requires one consent per
    // capture session).
    @Volatile private var savedProjectionCode = 0
    @Volatile private var savedProjectionData: Intent? = null
    @Volatile private var lastAutoHealAttemptMs = 0L
    // SPLENDOR_V42_AUTOHEAL_FIELDS_END
"""
apply_fix("D1. OverlayService auto-heal fields", OVERLAY, D1_OLD, D1_NEW)

# D2: save token on grant
D2_OLD = """        mediaProjection = mp

        val cb = object : MediaProjection.Callback() {
"""
D2_NEW = """        mediaProjection = mp

        // SPLENDOR_V42_AUTOHEAL_TOKENSAVE_BEGIN
        savedProjectionCode = code
        savedProjectionData = intent
        // SPLENDOR_V42_AUTOHEAL_TOKENSAVE_END

        val cb = object : MediaProjection.Callback() {
"""
apply_fix("D2. OverlayService save granted token", OVERLAY, D2_OLD, D2_NEW)

# D3: onStop -> auto-heal (state-aware, no re-setup loop)
D3_OLD = """        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Handler(Looper.getMainLooper()).post {
                    teardownCaptureResources(CaptureState.REVOKED)
                    RuntimeLogger.log("MediaProjection.onStop(): projection revoked; capture resources invalidated. AI Agent handling silently.", "OVERLAY")
                }
            }
        }
"""
D3_NEW = """        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                val stoppedMp = mediaProjection
                Handler(Looper.getMainLooper()).post {
                    // SPLENDOR_V42_AUTOHEAL_ONSTOP_BEGIN
                    // Ignore transient stops during re-setup (a newer projection
                    // already replaced this one) — prevents a heal loop.
                    if (stoppedMp != null && stoppedMp !== mediaProjection) return@post
                    // SPLENDOR_V42_AUTOHEAL_ONSTOP_END
                    teardownCaptureResources(CaptureState.REVOKED)
                    RuntimeLogger.log("MediaProjection.onStop(): projection revoked; auto-healing capture.", "OVERLAY")
                    // SPLENDOR_V42_AUTOHEAL_ONSTOP_BEGIN
                    // Auto-recover instead of waiting for a manual tap. Small
                    // delay lets the system settle after the revoke.
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { autoHealCapture() } catch (_: Throwable) {}
                    }, 2000L)
                    // SPLENDOR_V42_AUTOHEAL_ONSTOP_END
                }
            }
        }
"""
apply_fix("D3. OverlayService onStop auto-heal", OVERLAY, D3_OLD, D3_NEW)

# D4: autoHealCapture() method
D4_OLD = """    fun applyFreshProjection(code: Int, data: Intent) {
        captureLock.lock()
        try {
            setupMediaProjectionInternal(code, data)
            RuntimeLogger.log("AGENT CAPTURE RESTORED: Fresh MediaProjection token applied successfully", "AGENT")
        } finally {
            captureLock.unlock()
        }
    }
"""
D4_NEW = """    fun applyFreshProjection(code: Int, data: Intent) {
        captureLock.lock()
        try {
            setupMediaProjectionInternal(code, data)
            RuntimeLogger.log("AGENT CAPTURE RESTORED: Fresh MediaProjection token applied successfully", "AGENT")
        } finally {
            captureLock.unlock()
        }
    }

    // SPLENDOR_V42_AUTOHEAL_METHOD_BEGIN
    // Automatic capture recovery — no manual tap required.
    //  - Capture alive but stale: restart the ImageReader (no token, no tap).
    //  - Revoked/failed/idle: token reuse (zero interaction) when the session
    //    is still alive; otherwise auto-launch the system consent dialog
    //    (one tap — Android 14 requires one consent per capture session; no
    //    app can bypass that, but the app handles everything else itself).
    fun autoHealCapture(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAutoHealAttemptMs < 15_000L) return false  // anti-spam cooldown
        lastAutoHealAttemptMs = now
        val st = readCaptureState()
        if (st == CaptureState.ACTIVE || st == CaptureState.AUTHORIZED) {
            val ok = try { restartCapture() } catch (_: Throwable) { false }
            RuntimeLogger.log("AUTO_HEAL: capture alive but stale -> restartCapture() = " + ok, "OVERLAY")
            return ok
        }
        val code = savedProjectionCode
        val data = savedProjectionData
        if (code == 0 || data == null) {
            RuntimeLogger.log("AUTO_HEAL: no saved token; requesting fresh authorization", "OVERLAY")
            launchReauthActivity()
            return false
        }
        // Attempt 1: token reuse — zero user interaction.
        try {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = pm.getMediaProjection(code, data)
            if (mp != null) {
                captureLock.lock()
                try {
                    setupMediaProjectionInternal(code, data)
                    RuntimeLogger.log("AUTO_HEAL: token reuse succeeded — capture restored without user interaction", "OVERLAY")
                    return true
                } finally {
                    captureLock.unlock()
                }
            }
        } catch (t: Throwable) {
            RuntimeLogger.log("AUTO_HEAL: token reuse failed (" + t.javaClass.simpleName + ": " + t.message + ")", "OVERLAY")
        }
        // Attempt 2: auto re-prompt — one system consent tap (Android 14 rule).
        RuntimeLogger.log("AUTO_HEAL: token dead; auto-launching system consent (single tap)", "OVERLAY")
        launchReauthActivity()
        return false
    }

    private fun launchReauthActivity() {
        try {
            val i = Intent(this, SplendorReauthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (t: Throwable) {
            RuntimeLogger.log("AUTO_HEAL: reauth activity launch failed (" + t.javaClass.simpleName + ": " + t.message + ")", "OVERLAY")
        }
    }
    // SPLENDOR_V42_AUTOHEAL_METHOD_END
"""
apply_fix("D4. OverlayService autoHealCapture()", OVERLAY, D4_OLD, D4_NEW)

# D5: KEEP_SCREEN_ON
D5_OLD = """            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
"""
D5_NEW = """            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
"""
apply_fix("D5. OverlayService FLAG_KEEP_SCREEN_ON", OVERLAY, D5_OLD, D5_NEW)

# D6: companion static
D6_OLD = """        @JvmStatic
        fun requestRecoveryPrompt() {
            instance?.showCaptureRecoveryPrompt()
        }
    }
"""
D6_NEW = """        @JvmStatic
        fun requestRecoveryPrompt() {
            instance?.showCaptureRecoveryPrompt()
        }

        // SPLENDOR_V42_AUTOHEAL_STATIC_BEGIN
        @JvmStatic
        fun autoHealCapture(): Boolean =
            instance?.autoHealCapture() ?: false
        // SPLENDOR_V42_AUTOHEAL_STATIC_END
    }
"""
apply_fix("D6. OverlayService companion autoHealCapture", OVERLAY, D6_OLD, D6_NEW)

# =====================================================================
# FIX E — SplendorCaptureRecovery.kt: no false-positive prompt spam
# =====================================================================
E1_OLD = """        if (System.currentTimeMillis() - lastFrame > 8000) { dead = true; onRevoked() }
"""
E1_NEW = """        if (System.currentTimeMillis() - lastFrame > 30000) { dead = true; onRevoked() }
"""
apply_fix("E1. Recovery staleness 8s -> 30s", RECOVERY, E1_OLD, E1_NEW)

E2_OLD = """    fun markFrame() { lastFrame = System.currentTimeMillis(); armed = true }
"""
E2_NEW = """    fun markFrame() { lastFrame = System.currentTimeMillis(); armed = true; dead = false }
"""
apply_fix("E2. Recovery re-arm on frame resume", RECOVERY, E2_OLD, E2_NEW)

E3_OLD = """    private fun onRevoked() {
        val svc = svcRef?.get() ?: return
        Log.w(TAG, "capture stale -> requesting fresh user authorization and forcing overlay prompt")
        
        // EMPOWERED: Force the overlay prompt immediately so user cannot miss it
        try {
            com.assistant.OverlayService.requestRecoveryPrompt()
        } catch (_: Throwable) {}

        val i = Intent(svc, SplendorReauthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(svc, 4242, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = Notification.Builder(svc, CH)
            .setContentTitle("Splendor capture dead")
            .setContentText("Tap to re-authorize screen capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi).setOngoing(true)
        svc.getSystemService(NotificationManager::class.java).notify(777, nb.build())
    }
"""
E3_NEW = """    private fun onRevoked() {
        if (svcRef?.get() == null) return
        Log.w(TAG, "capture stale -> auto-healing capture (no manual tap required)")
        // SPLENDOR_V42_AUTOHEAL_REVOKED_BEGIN
        // v2: automatic recovery — token reuse first (zero tap), then the
        // system consent dialog (one tap). No overlay prompt / notification spam.
        try {
            com.assistant.OverlayService.autoHealCapture()
        } catch (_: Throwable) {}
        // SPLENDOR_V42_AUTOHEAL_REVOKED_END
    }
"""
apply_fix("E3. Recovery onRevoked -> auto-heal", RECOVERY, E3_OLD, E3_NEW)

# =====================================================================
# FIX F — Android 14 service-first ordering
# =====================================================================
# F1: MainActivity field
F1_OLD = """    private var permissionPipelineStarted = false
    private var permissionPipelineActive = false
    private var projectionRecoveryFlow = false
"""
F1_NEW = """    private var permissionPipelineStarted = false
    private var permissionPipelineActive = false
    private var projectionRecoveryFlow = false
    // SPLENDOR_V42_FG_FIRST_FIELD_BEGIN
    // Android 14 (targetSdk 34): the mediaProjection-type foreground service
    // must be running BEFORE createScreenCaptureIntent(), otherwise the
    // projection is revoked as soon as the app goes to background.
    private var serviceStartedInWaitingMode = false
    // SPLENDOR_V42_FG_FIRST_FIELD_END
"""
apply_fix("F1. MainActivity waiting-mode flag", MAINACT, F1_OLD, F1_NEW)

# F2: start service before consent
F2_OLD = """    private fun checkMediaProjectionAndProceed() {
        permissionStage = PermissionStage.MEDIA_PROJECTION
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
"""
F2_NEW = """    private fun checkMediaProjectionAndProceed() {
        permissionStage = PermissionStage.MEDIA_PROJECTION
        // SPLENDOR_V42_FG_FIRST_START_BEGIN
        // Start the mediaProjection-type foreground service BEFORE the consent
        // dialog (Android 14 requirement). The service waits for the token.
        try {
            val waitIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("WAITING_FOR_TOKEN", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(waitIntent)
            } else {
                startService(waitIntent)
            }
            serviceStartedInWaitingMode = true
        } catch (_: Throwable) {}
        // SPLENDOR_V42_FG_FIRST_START_END
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
"""
apply_fix("F2. MainActivity service-first ordering", MAINACT, F2_OLD, F2_NEW)

# F3: success branch reset
F3_OLD = """            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                permissionPipelineActive = false
"""
F3_NEW = """            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                permissionPipelineActive = false
                // SPLENDOR_V42_FG_FIRST_RESET_BEGIN
                serviceStartedInWaitingMode = false
                // SPLENDOR_V42_FG_FIRST_RESET_END
"""
apply_fix("F3. MainActivity success reset", MAINACT, F3_OLD, F3_NEW)

# F4: cancel branch stops waiting service
F4_OLD = """            } else {
                permissionPipelineActive = false
                permissionStage = PermissionStage.COMPLETE
                Toast.makeText(this, "MediaProjection permission cancelled", Toast.LENGTH_SHORT).show()
            }
"""
F4_NEW = """            } else {
                permissionPipelineActive = false
                permissionStage = PermissionStage.COMPLETE
                Toast.makeText(this, "MediaProjection permission cancelled", Toast.LENGTH_SHORT).show()
                // SPLENDOR_V42_FG_FIRST_CANCEL_BEGIN
                // Stop the waiting-mode service if this was a fresh start
                // (never touch an already-active capture).
                if (serviceStartedInWaitingMode && !projectionRecoveryFlow) {
                    serviceStartedInWaitingMode = false
                    try { stopService(Intent(this, OverlayService::class.java)) } catch (_: Throwable) {}
                }
                // SPLENDOR_V42_FG_FIRST_CANCEL_END
            }
"""
apply_fix("F4. MainActivity cancel stops waiting service", MAINACT, F4_OLD, F4_NEW)

# F5: OverlayService waiting mode
F5_OLD = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("CROSS_PROCESS_CODE", EngineData.code) ?: EngineData.code
"""
F5_NEW = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // SPLENDOR_V42_FG_FIRST_WAIT_BEGIN
        // Android 14 service-first ordering: the service may be started in
        // WAITING_FOR_TOKEN mode (before the consent dialog) or told to stop
        // itself if the user cancelled consent.
        if (intent?.getBooleanExtra("WAITING_FOR_TOKEN", false) == true) {
            startForegroundSafely()
            RuntimeLogger.log("OverlayService started in WAITING_FOR_TOKEN mode (Android 14 service-first ordering)", "OVERLAY")
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra("CANCEL_TOKEN", false) == true) {
            if (mediaProjection == null && readCaptureState() == CaptureState.IDLE) {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // SPLENDOR_V42_FG_FIRST_WAIT_END
        val resultCode = intent?.getIntExtra("CROSS_PROCESS_CODE", EngineData.code) ?: EngineData.code
"""
apply_fix("F5. OverlayService waiting mode", OVERLAY, F5_OLD, F5_NEW)

# =====================================================================
# FIX G — Manifest: declare SplendorReauthActivity (was missing)
# =====================================================================
G_OLD = """        <activity android:name="com.assistant.DiagnosisDetailActivity" android:exported="false" />
"""
G_NEW = """        <activity android:name="com.assistant.SplendorReauthActivity" android:exported="false" android:theme="@android:style/Theme.Translucent.NoTitleBar" />
        <activity android:name="com.assistant.DiagnosisDetailActivity" android:exported="false" />
"""
apply_fix("G. Manifest declare SplendorReauthActivity", MANIFEST, G_OLD, G_NEW)

# =====================================================================
# Summary
# =====================================================================
print()
print("-" * 70)
print("PATCH RESULTS")
print("-" * 70)
ok = 0
skipped = 0
for name, status, detail in results:
    print("  [%s] %s — %s" % (status, name, detail))
    if status == "OK":
        ok += 1
    elif status == "SKIP":
        skipped += 1
print("-" * 70)
if ok + skipped == len(results) and len(results) > 0:
    print("ALL %d FIXES APPLIED AND VERIFIED (%d applied, %d already present)." % (len(results), ok, skipped))
    print("Next: ./gradlew assembleRelease  (or assembleDebug)")
    print("Verify .so in APK: unzip -l app/build/outputs/apk/release/app-release.apk | grep splendor_native")
else:
    print("SOME FIXES FAILED — review the FAIL lines above before building.")
    sys.exit(1)

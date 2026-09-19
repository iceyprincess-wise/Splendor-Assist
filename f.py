#!/usr/bin/env python3
from pathlib import Path
import os, re, shutil, subprocess, sys, tempfile

ROOT = Path.cwd()
REPO_EXPECTED = "iceyprincess-wise/Splendor-Assist"

def run(cmd, check=True):
    print("+", " ".join(map(str, cmd)))
    return subprocess.run([str(x) for x in cmd], cwd=ROOT, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          check=check)

def fail(msg):
    print("[ABORT]", msg)
    raise SystemExit(1)

def replace_once(path, old, new, label):
    s = path.read_text(encoding="utf-8")
    n = s.count(old)
    if n != 1:
        fail(f"{label}: expected 1 match in {path}, found {n}")
    path.write_text(s.replace(old, new, 1), encoding="utf-8")

def backup(path, backup_root):
    if path.exists():
        dst = backup_root / path.relative_to(ROOT)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dst)

def write_file(path, content, backup_root):
    backup(path, backup_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")

def norm_remote(url):
    u = url.strip().rstrip("/")
    u = re.sub(r"^git@github\.com:", "https://github.com/", u)
    u = re.sub(r"^ssh://git@github\.com/", "https://github.com/", u)
    return u[:-4] if u.endswith(".git") else u

def find_bin(names):
    for n in names:
        p = shutil.which(n)
        if p:
            return p
    roots = []
    for env in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        if os.environ.get(env):
            roots.append(Path(os.environ[env]))
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        roots += sorted((Path(sdk) / "ndk").glob("*"), reverse=True)
    for root in roots:
        for n in names:
            p = root / "toolchains/llvm/prebuilt/linux-aarch64/bin" / n
            if p.exists() and os.access(p, os.X_OK):
                return str(p)
    return None

if not (ROOT / ".git").exists():
    fail("Run from the Splendor-Assist repository root.")

backup_root = Path(tempfile.mkdtemp(prefix="splendor_runtime_fix_"))
print("[BACKUP]", backup_root)

bridge = ROOT / "app/src/main/java/com/assistant/NativeBridge.kt"
agility = ROOT / "app/src/main/cpp/native_agility_physics.c"
kicking = ROOT / "app/src/main/cpp/native_kicking_posture.c"
registration = ROOT / "app/src/main/cpp/native_registration.c"
cmake = ROOT / "app/src/main/cpp/CMakeLists.txt"
overlay = ROOT / "app/src/main/java/com/assistant/OverlayService.kt"
gameplay = ROOT / "app/src/main/java/com/assistant/gameplay_engine.kt"
smart = ROOT / "app/src/main/java/com/assistant/SmartAssistAccessibilityEngine.kt"
registry = ROOT / "core/src/main/java/com/assistant/runtime/GameplayEngineRegistry.kt"

# 1) Native boundary: explicit JNI registration, one-time load diagnosis,
# and deterministic no-exception fallback.
bridge_text = r'''package com.assistant import com.assistant.diagnostic.RuntimeLogger import java.util.concurrent.ThreadLocalRandom import java.util.concurrent.atomic.AtomicBoolean object NativeBridge { private const val LIBRARY = "splendor_native" private val nativeReady = AtomicBoolean(false) @Volatile private var nativeFailure = "" @Volatile private var failureLogged = false init { try { System.loadLibrary(LIBRARY) nativeReady.set(true) RuntimeLogger.log( "NativeBridge: splendor_native loaded; explicit JNI registration active", "RUNTIME" ) } catch (t: Throwable) { nativeFailure = "${t.javaClass.simpleName}: ${t.message ?: "unknown"}" logFailureOnce() } } @JvmStatic fun nativeKickingPosture( carrierX: Float, carrierY: Float, carrierVx: Float, carrierVy: Float, targetX: Float, targetY: Float, pitchWidth: Float, pitchHeight: Float, outBuffer: FloatArray ) { if (outBuffer.size < 4) return if (nativeReady.get()) { try { nativeKickingPostureImpl( carrierX, carrierY, carrierVx, carrierVy, targetX, targetY, pitchWidth, pitchHeight, outBuffer ) return } catch (t: Throwable) { nativeReady.set(false) nativeFailure = "${t.javaClass.simpleName}: ${t.message ?: "unknown"}" logFailureOnce() } } outBuffer[0] = targetX.coerceIn(0f, pitchWidth.coerceAtLeast(0f)) outBuffer[1] = targetY.coerceIn(0f, pitchHeight.coerceAtLeast(0f)) outBuffer[2] = 1f outBuffer[3] = 0f } @JvmStatic fun nativeAgilityPhysics( playerVelocity: Float, opponentDistance: Float, movementAngleDegrees: Float, possessionConfidence: Float, turnIntensity: Float, playerX: Float, playerY: Float, oppX: Float, oppY: Float, threadSeed: Int, outBuffer: FloatArray ) { if (outBuffer.size < 6) return if (nativeReady.get()) { try { nativeAgilityPhysicsImpl( playerVelocity, opponentDistance, movementAngleDegrees, possessionConfidence, turnIntensity, playerX, playerY, oppX, oppY, threadSeed, outBuffer ) return } catch (t: Throwable) { nativeReady.set(false) nativeFailure = "${t.javaClass.simpleName}: ${t.message ?: "unknown"}" logFailureOnce() } } outBuffer[0] = 1.5f outBuffer[1] = possessionConfidence.coerceIn(0f, 1f) outBuffer[2] = 0f outBuffer[3] = 0f outBuffer[4] = 45f outBuffer[5] = 0f } @JvmStatic fun nextThreadSeed(): Int = ThreadLocalRandom.current().nextInt() @JvmStatic fun isNativeAvailable(): Boolean = nativeReady.get() @JvmStatic fun nativeFailure(): String = nativeFailure private fun logFailureOnce() { synchronized(this) { if (failureLogged) return failureLogged = true } try { RuntimeLogger.log( "NATIVE LINK FAILURE: $LIBRARY unavailable; deterministic Kotlin fallback active; $nativeFailure", "RUNTIME" ) } catch (_: Throwable) {} } @JvmStatic private external fun nativeKickingPostureImpl( carrierX: Float, carrierY: Float, carrierVx: Float, carrierVy: Float, targetX: Float, targetY: Float, pitchWidth: Float, pitchHeight: Float, outBuffer: FloatArray ) @JvmStatic private external fun nativeAgilityPhysicsImpl( playerVelocity: Float, opponentDistance: Float, movementAngleDegrees: Float, possessionConfidence: Float, turnIntensity: Float, playerX: Float, playerY: Float, oppX: Float, oppY: Float, threadSeed: Int, outBuffer: FloatArray ) } '''
write_file(bridge, bridge_text, backup_root)

replace_once(agility, "Java_com_assistant_NativeBridge_nativeAgilityPhysics(",
             "Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl(", "Agility JNI rename")
replace_once(agility, "JNIEnv* env, jobject thiz,",
             "JNIEnv* env, jclass /*clazz*/,", "Agility static JNI receiver")
replace_once(kicking, "Java_com_assistant_NativeBridge_nativeKickingPosture(",
             "Java_com_assistant_NativeBridge_nativeKickingPostureImpl(", "Kicking JNI rename")
replace_once(kicking, "JNIEnv* env, jobject thiz,",
             "JNIEnv* env, jclass /*clazz*/,", "Kicking static JNI receiver")

registration_text = r'''#include <jni.h> JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeKickingPostureImpl( JNIEnv*, jclass, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloatArray); JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl( JNIEnv*, jclass, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jint, jfloatArray); JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) { JNIEnv* env = NULL; if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) { return JNI_ERR; } jclass bridge = (*env)->FindClass(env, "com/assistant/NativeBridge"); if (bridge == NULL) return JNI_ERR; static const JNINativeMethod methods[] = { {"nativeKickingPostureImpl", "(FFFFFFFF[F)V", (void*)Java_com_assistant_NativeBridge_nativeKickingPostureImpl}, {"nativeAgilityPhysicsImpl", "(FFFFFFFFFI[F)V", (void*)Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl} }; const jint rc = (*env)->RegisterNatives( env, bridge, methods, (jint)(sizeof(methods) / sizeof(methods[0])) ); (*env)->DeleteLocalRef(env, bridge); return rc == 0 ? JNI_VERSION_1_6 : JNI_ERR; } '''
write_file(registration, registration_text, backup_root)

replace_once(cmake,
    "add_library(splendor_native SHARED native_input.cpp native_kicking_posture.c native_agility_physics.c)",
    "add_library(splendor_native SHARED native_input.cpp native_kicking_posture.c native_agility_physics.c native_registration.c)",
    "CMake JNI registration")

# 2) Overlay: eliminate avoidable per-frame direct-buffer allocation/copy when
# the single Vision slot is already busy; preserve OCR buffer position.
replace_once(overlay,
    "@Volatile private var lastFrameProcessedMs = 0L\n private val visionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)",
    "@Volatile private var lastFrameProcessedMs = 0L\n private val visionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)\n private var reusableVisionBuffer: ByteBuffer? = null",
    "Overlay reusable vision field")

old_capture = ''' val rowStride = plane.rowStride val pixelStride = plane.pixelStride val originalBuffer = plane.buffer // Deep copy for Vision Pipeline (async safe) val visionBuffer = ByteBuffer.allocateDirect(originalBuffer.remaining()) visionBuffer.put(originalBuffer) visionBuffer.flip() val startVision = visionInFlight.compareAndSet(false, true) // Deep copy for OCR (sync safe via reusableBitmap) val ocrReady = try { if (reusableBitmap == null || reusableBitmap!!.width != width || reusableBitmap!!.height != height) { reusableBitmap?.recycle() reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } reusableBitmap!!.copyPixelsFromBuffer(originalBuffer) true } catch (_: Throwable) { false } // CLOSE IMAGE IMMEDIATELY: Frees native surface, prevents queue backup and faults image.close() // Launch Vision Coroutine with SAFE copied buffer if (startVision) visionScope.launch { try { val normalized = com.assistant.FrameNormalizer.normalize(visionBuffer, width, height, rowStride, pixelStride) '''
new_capture = ''' val rowStride = plane.rowStride val pixelStride = plane.pixelStride val sourceBuffer = plane.buffer.duplicate().apply { rewind() } // Reserve the single Vision slot before copying. Busy frames are dropped // without allocating/copying another full direct buffer. val startVision = visionInFlight.compareAndSet(false, true) val visionBuffer = if (startVision) copyIntoReusableVisionBuffer(sourceBuffer) else null // OCR uses an independent rewinded view; Vision must not consume its bytes. val ocrReady = try { if (reusableBitmap == null || reusableBitmap!!.width != width || reusableBitmap!!.height != height) { reusableBitmap?.recycle() reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } reusableBitmap!!.copyPixelsFromBuffer(sourceBuffer.duplicate().apply { rewind() }) true } catch (_: Throwable) { false } // CLOSE IMAGE IMMEDIATELY: Frees native surface, prevents queue backup and faults image.close() // Launch Vision Coroutine with SAFE copied buffer if (startVision && visionBuffer != null) visionScope.launch { try { val normalized = com.assistant.FrameNormalizer.normalize(visionBuffer, width, height, rowStride, pixelStride) '''
replace_once(overlay, old_capture, new_capture, "Overlay capture ownership")

replace_once(overlay,
    " fun applyFreshProjection(code: Int, data: Intent) {",
    ''' private fun copyIntoReusableVisionBuffer(source: ByteBuffer): ByteBuffer { val needed = source.remaining() var target = reusableVisionBuffer if (target == null || target.capacity() < needed) { target = ByteBuffer.allocateDirect(needed) reusableVisionBuffer = target } target.clear() target.limit(needed) target.put(source.duplicate().apply { rewind() }) target.flip() return target } fun applyFreshProjection(code: Int, data: Intent) {''',
    "Overlay reusable buffer helper")

replace_once(overlay,
''' override fun onStop() { super.onStop() Handler(Looper.getMainLooper()).post { teardownCaptureResources(CaptureState.REVOKED) RuntimeLogger.log("MediaProjection.onStop(): projection revoked; capture resources invalidated. AI Agent handling silently.", "OVERLAY") } } ''',
''' override fun onStop() { super.onStop() Handler(Looper.getMainLooper()).post { teardownCaptureResources(CaptureState.REVOKED) RuntimeCoordinator.reportCaptureLost() RuntimeLogger.log( "MediaProjection.onStop(): projection revoked; capture gate closed and execution paused.", "OVERLAY" ) } } ''',
    "Overlay projection loss")

replace_once(overlay,
''' if (mp == null) { captureState = CaptureState.FAILED RuntimeLogger.log("MediaProjection setup failed: getMediaProjection returned null", "OVERLAY") throw IllegalStateException("MediaProjection unavailable") } ''',
''' if (mp == null) { captureState = CaptureState.FAILED RuntimeCoordinator.reportCaptureLost() RuntimeLogger.log("MediaProjection setup failed: getMediaProjection returned null", "OVERLAY") throw IllegalStateException("MediaProjection unavailable") } ''',
    "Overlay projection setup failure")

# 3) RuntimeCoordinator capture-loss gate; health sees real capture state.
replace_once(gameplay,
''' @Synchronized fun reportAccessibilityLost() { ''',
''' @Synchronized fun reportCaptureLost() { val wasReady = captureReady.getAndSet(false) busEnabled.set(false) runtimeReady.set(false) try { CentralExecutionBus.stop() } catch (_: Throwable) {} if (wasReady) transition("G2 CAPTURE_LOST") } @JvmStatic fun isExecutionReady(): Boolean = accessibilityReady.get() && captureReady.get() && busEnabled.get() && runtimeReady.get() @Synchronized fun reportAccessibilityLost() { ''',
    "RuntimeCoordinator capture loss")

replace_once(gameplay,
''' val overlayAlive = runtime["captureReady"] as? Boolean ?: false ''',
''' val captureState = try { com.assistant.OverlayService.captureState().name } catch (_: Throwable) { "UNKNOWN" } val overlayAlive = (runtime["captureReady"] as? Boolean ?: false) && captureState == "ACTIVE" ''',
    "Runtime health capture truth")

replace_once(gameplay,
''' val gameplayAlive: Boolean, val boosterAlive: Boolean, ''',
''' val gameplayAlive: Boolean, val contributorFailures: Long, val boosterAlive: Boolean, ''',
    "Health contributor failure field")
replace_once(gameplay,
''' val registry = GameplayEngineRegistry.registryRuntimeSnapshot() val accessibilityAlive = ''',
''' val registry = GameplayEngineRegistry.registryRuntimeSnapshot() val contributorFailures = (registry["failureTotal"] as? Number)?.toLong() ?: 0L val accessibilityAlive = ''',
    "Health contributor failure read")
replace_once(gameplay,
''' if (!gameplayAlive) degraded += "gameplay-idle" if (!boosterAlive) degraded += "booster-not-ready" ''',
''' if (!gameplayAlive) degraded += "gameplay-idle" if (contributorFailures > 0L) degraded += "contributor-failures" if (!boosterAlive) degraded += "booster-not-ready" ''',
    "Health contributor degradation")
replace_once(gameplay,
''' dispatchAlive = dispatchAlive, gameplayAlive = gameplayAlive, boosterAlive = boosterAlive, ''',
''' dispatchAlive = dispatchAlive, gameplayAlive = gameplayAlive, contributorFailures = contributorFailures, boosterAlive = boosterAlive, ''',
    "HealthState construction")
replace_once(gameplay,
''' "gameplayAlive" to state.gameplayAlive, "boosterAlive" to state.boosterAlive, ''',
''' "gameplayAlive" to state.gameplayAlive, "contributorFailures" to state.contributorFailures, "boosterAlive" to state.boosterAlive, ''',
    "Health snapshot")
replace_once(gameplay,
''' // V6 PROMOTION: booster-not-ready now has a SAFE automated recovery // (re-ignite fleet) instead of permanent ObserveOnly. if (!health.boosterAlive) { ''',
''' if (health.contributorFailures > 0L) { return AgentDecision( action = AgentAction.RunSelfHealCheck, priority = 80, reason = "Gameplay contributor failures detected; run self-heal diagnostics." ) } // V6 PROMOTION: booster-not-ready now has a SAFE automated recovery // (re-ignite fleet) instead of permanent ObserveOnly. if (!health.boosterAlive) { ''',
    "Agent contributor failure policy")

# Self-heal sees contributor failure totals and uses the real registration count.
replace_once(gameplay,
''' @Volatile private var prevCollectCycles: Long = 0L private fun checkContributors(warmed: Boolean) { ''',
''' @Volatile private var prevCollectCycles: Long = 0L @Volatile private var prevContributorFailures: Long = 0L private fun checkContributors(warmed: Boolean) { ''',
    "Self-heal contributor tracker")
replace_once(gameplay,
''' val cls = Class.forName("com.assistant.runtime.GameplayEngineRegistry") @Suppress("UNCHECKED_CAST") val snap = cls.getMethod("registryRuntimeSnapshot").invoke(null) as? Map<String, Any> ?: return val engines = (snap["engines"] as? Int) ?: return val cycles = (snap["collectCycles"] as? Long) ?: return val delta = cycles - prevCollectCycles prevCollectCycles = cycles // PHASE4B: COLLECT_STALL''',
''' // GameplayEngineRegistry is a Kotlin object; call its instance method directly. val snap = com.assistant.runtime.GameplayEngineRegistry.registryRuntimeSnapshot() val engines = (snap["engines"] as? Int) ?: return val cycles = (snap["collectCycles"] as? Long) ?: return val failureTotal = (snap["failureTotal"] as? Number)?.toLong() ?: 0L if (failureTotal > prevContributorFailures && shouldLog("CONTRIBUTOR_FAILURES", "total=$failureTotal")) { record(HealEvent( timestamp = fmt.format(Date()), category = "CONTRIBUTOR_FAILURES", detected = "Gameplay contributor failures increased; total=$failureTotal.", fix = "NativeBridge records native link failure once and uses a deterministic fallback; agent health now sees contributor failures.", severity = "CRITICAL" )) } prevContributorFailures = failureTotal val delta = cycles - prevCollectCycles prevCollectCycles = cycles // PHASE4B: COLLECT_STALL''',
    "Self-heal contributor failure diagnostics")
replace_once(gameplay,
''' if (engines < 29 && warmed && shouldLog("REGISTRY_GAP", "engines=$engines")) { record(HealEvent( timestamp = fmt.format(Date()), category = "REGISTRY_GAP", detected = "Only $engines/29 contributors registered. Missing ${29 - engines}. " + ''',
''' val expected = try { com.assistant.AppContributorRegistration.EXPECTED_CONTRIBUTOR_COUNT } catch (_: Throwable) { 0 } if (expected > 0 && engines < expected && warmed && shouldLog("REGISTRY_GAP", "engines=$engines expected=$expected")) { record(HealEvent( timestamp = fmt.format(Date()), category = "REGISTRY_GAP", detected = "Only $engines/$expected contributors registered. Missing ${expected - engines}. " + ''',
    "Self-heal registry threshold")

# Revoke/fail/idle capture already has a user recovery prompt API; actually invoke it.
for state_name in ("REVOKED", "FAILED", "IDLE"):
    marker = f''' }} else if (state == com.assistant.OverlayService.CaptureState.{state_name}) {{ if (now - lastRestartAttemptMs > 30_000L || lastRestartAttemptMs == 0L) {{ lastRestartAttemptMs = now '''
    if gameplay.read_text(encoding="utf-8").count(marker) != 1:
        fail(f"Capture branch anchor missing for {state_name}")
    replace_once(gameplay, marker, marker +
                 " try { com.assistant.OverlayService.requestRecoveryPrompt() } catch (_: Throwable) {}\n",
                 f"Capture recovery prompt {state_name}")

# 4) Registry exposes actual contribution failure counts to health/agent.
replace_once(registry,
''' "warmUpTimestamp" to warmUpCompletionTimestamp, "sessionEpoch" to sessionEpoch.get() ''',
''' "warmUpTimestamp" to warmUpCompletionTimestamp, "sessionEpoch" to sessionEpoch.get(), "failureTotal" to failures.values.sum(), "failedEngines" to failures.count { it.value > 0L } ''',
    "Registry failure telemetry")

# 5) Execution bus refuses to consume after capture-loss until G6 is restored.
replace_once(smart,
''' if (!SmartAssistRepository.enabled()) { busHandler.postDelayed(this, BUS_POLL_RATE_MS) return } ''',
''' if (!RuntimeCoordinator.isExecutionReady()) { busHandler.postDelayed(this, BUS_POLL_RATE_MS) return } if (!SmartAssistRepository.enabled()) { busHandler.postDelayed(this, BUS_POLL_RATE_MS) return } ''',
    "Bus runtime-ready gate")

# 6) FrameScanner: honor rowStride/pixelStride; never scan row padding as pixels.
gt = gameplay.read_text(encoding="utf-8")
start = gt.find(' fun scan(\n frame: FrameNormalizer.NormalizedFrame,\n', gt.find("object FrameScanner"))
if start < 0:
    fail("FrameScanner.scan start not found")
end = gt.find('\n}\n/* ======\nFrameScanner Anchor\n======\n', start)
if end < 0:
    fail("FrameScanner object end not found")
current = gt[start:end]
if "while (index + 3 < limit)" not in current:
    fail("FrameScanner is not the expected pre-patch implementation")
scan = ''' fun scan( frame: FrameNormalizer.NormalizedFrame, threshold: Float = 0.50f, adaptiveNoiseVariance: Int = 0, serverTickSyncScale: Float = 1.0f ): PixelSampleBuffer { val width = frame.width val height = frame.height val pixelStride = frame.pixelStride val rowStride = frame.rowStride if (width <= 0 || height <= 0 || pixelStride < 3 || rowStride <= 0) { return PixelSampleBuffer(LongArray(0), 0) } val source = frame.buffer.duplicate().apply { rewind() } val rowPixelBytes = width * pixelStride val requiredBytes = (height - 1) * rowStride + rowPixelBytes if (rowPixelBytes <= 0 || requiredBytes <= 0 || source.remaining() < requiredBytes) { return PixelSampleBuffer(LongArray(0), 0) } // Compact valid pixels from each row. ImageReader may add row padding. var frameBytes = reusableByteArray val compactBytes = rowPixelBytes * height if (frameBytes == null || frameBytes.size < compactBytes) { frameBytes = ByteArray(compactBytes) reusableByteArray = frameBytes } for (y in 0 until height) { source.position(y * rowStride) source.get(frameBytes, y * rowPixelBytes, rowPixelBytes) } val thresholdInt = (threshold * 255.0f).roundToInt().coerceIn(0, 255) val maxPixels = width * height var samples = reusableSamples if (samples == null || samples.size < maxPixels) { samples = LongArray(maxPixels) reusableSamples = samples } var sampleCount = 0 for (y in 0 until height) { var x = 0 var index = y * rowPixelBytes while (x < width) { val r = frameBytes[index].toInt() and 0xFF val g = frameBytes[index + 1].toInt() and 0xFF val b = frameBytes[index + 2].toInt() and 0xFF val lumInt = (r * WEIGHT_R + g * WEIGHT_G + b * WEIGHT_B) shr LUMINANCE_SHIFT if (lumInt >= thresholdInt) { var finalX = x var finalY = y if (adaptiveNoiseVariance > 0 || serverTickSyncScale != 1.0f) { val noiseX = if (adaptiveNoiseVariance > 0) { Random.nextInt(-adaptiveNoiseVariance, adaptiveNoiseVariance + 1) } else 0 val noiseY = if (adaptiveNoiseVariance > 0) { Random.nextInt(-adaptiveNoiseVariance, adaptiveNoiseVariance + 1) } else 0 finalX = ((x + noiseX) * serverTickSyncScale) .roundToInt().coerceIn(0, width - 1) finalY = ((y + noiseY) * serverTickSyncScale) .roundToInt().coerceIn(0, height - 1) } samples[sampleCount++] = (finalX.toLong() shl 40) or (finalY.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong() } x++ index += pixelStride } } return PixelSampleBuffer(samples, sampleCount) } '''
gameplay.write_text(gt[:start] + scan + gt[end:], encoding="utf-8")
replace_once(gameplay,
    'RuntimeLogger.log("Pure Kotlin VisionPreprocessor active (NDK TLS bypass)", TAG)',
    'RuntimeLogger.log("Stride-aware Kotlin VisionPreprocessor active", TAG)',
    "Vision log truth")

# 7) Static assertions.
for p, needle in [
    (bridge, "nativeKickingPostureImpl"),
    (bridge, "nativeAgilityPhysicsImpl"),
    (registration, "JNI_OnLoad"),
    (registration, "RegisterNatives"),
    (cmake, "native_registration.c"),
    (agility, "Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl"),
    (kicking, "Java_com_assistant_NativeBridge_nativeKickingPostureImpl"),
    (overlay, "copyIntoReusableVisionBuffer"),
    (overlay, "RuntimeCoordinator.reportCaptureLost()"),
    (gameplay, "contributorFailures"),
    (gameplay, "Stride-aware Kotlin VisionPreprocessor active"),
    (smart, "RuntimeCoordinator.isExecutionReady()"),
    (registry, "failureTotal"),
]:
    if needle not in p.read_text(encoding="utf-8"):
        fail(f"Post-patch assertion failed: {needle} not found in {p}")

print("[PATCH] source edits complete")

# 8) Force CMake/native regeneration and build.
run(["./gradlew", "--stop"], check=False)
for rel in ("app/.cxx", "app/build"):
    p = ROOT / rel
    if p.exists():
        shutil.rmtree(p)

build = run(["./gradlew", "clean", ":app:assembleDebug", "--no-daemon", "--stacktrace"], check=False)
print(build.stdout)
if build.returncode != 0:
    fail("Gradle build failed")

apk_dir = ROOT / "app/build/outputs/apk/debug"
apks = sorted(apk_dir.glob("*.apk"))
if not apks:
    fail("No debug APK produced")
apk = apks[-1]

unzip = shutil.which("unzip")
if not unzip:
    fail("unzip not found; APK probe cannot run")
listing = run([unzip, "-l", apk]).stdout
so_member = "lib/arm64-v8a/libsplendor_native.so"
if so_member not in listing:
    fail(f"APK missing {so_member}")

probe = Path(tempfile.mkdtemp(prefix="splendor_elf_probe_"))
try:
    run([unzip, "-o", str(apk), so_member, "-d", probe])
    so = probe / so_member
    tool = find_bin(["llvm-nm", "nm"])
    if tool:
        symbols = run([tool, "-D", "--defined-only", so], check=False).stdout
    else:
        tool = find_bin(["llvm-readelf", "readelf"])
        if not tool:
            fail("No nm/readelf available for ELF proof")
        symbols = run([tool, "-Ws", so], check=False).stdout

    required = [
        "Java_com_assistant_NativeBridge_nativeKickingPostureImpl",
        "Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl",
        "JNI_OnLoad",
    ]
    missing = [x for x in required if x not in symbols]
    if missing:
        print(symbols)
        fail("ELF symbol probe failed: " + ", ".join(missing))

    print("[ELF] required JNI symbols present")
    for x in required:
        print(" +", x)
finally:
    shutil.rmtree(probe, ignore_errors=True)

run(["git", "diff", "--check"])
print("\n====================================================")
print("PATCH + BUILD + APK + ELF PROBE PASSED")
print("====================================================")
print("HEAD :", head)
print("APK :", apk)
print("BACKUP:", backup_root)
print("PUSH : NOT PERFORMED")
print("\nReview:")
print(" git status --short")
print(" git diff --stat")
print(" git diff")

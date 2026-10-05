#!/usr/bin/env python3
import sys
import os

print("=== SPLENDOR-ASSIST PYTHON3 PATCH SCRIPT (f1.py) ===")

# Target 1: app/src/main/cpp/native_agility_physics.c
agility_c_path = os.path.join("app", "src", "main", "cpp", "native_agility_physics.c")
if not os.path.exists(agility_c_path):
    print(f"FAIL: File not found: {agility_c_path}")
    sys.exit(1)

with open(agility_c_path, "r", encoding="utf-8") as f:
    agility_c_content = f.read()

target1_search = """JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl(
        JNIEnv* env, jclass clazz,
        jfloat playerVelocity, jfloat opponentDistance,
        jfloat movementAngleDegrees, jfloat possessionConfidence,
        jfloat turnIntensity, jfloat playerX, jfloat playerY,
        jfloat oppX, jfloat oppY, jint threadSeed, jfloatArray outBuffer) {

    jfloat* result = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);"""

target1_replace = """JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl(
        JNIEnv* env, jclass clazz,
        jfloat playerVelocity, jfloat opponentDistance,
        jfloat movementAngleDegrees, jfloat possessionConfidence,
        jfloat turnIntensity, jfloat playerX, jfloat playerY,
        jfloat oppX, jfloat oppY, jint threadSeed, jfloatArray outBuffer) {

    if (!outBuffer || (*env)->GetArrayLength(env, outBuffer) < 6) return;

    jfloat* result = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);"""

if "GetArrayLength(env, outBuffer) < 6" in agility_c_content:
    print("native_agility_physics.c: Already patched.")
elif target1_search in agility_c_content:
    agility_c_content = agility_c_content.replace(target1_search, target1_replace)
    with open(agility_c_path, "w", encoding="utf-8") as f:
        f.write(agility_c_content)
    print("native_agility_physics.c: Successfully patched.")
else:
    print("FAIL: Expected pattern not found in native_agility_physics.c")
    sys.exit(1)

# Target 2: app/src/main/cpp/native_gameplay_compute.c
gameplay_c_path = os.path.join("app", "src", "main", "cpp", "native_gameplay_compute.c")
if not os.path.exists(gameplay_c_path):
    print(f"FAIL: File not found: {gameplay_c_path}")
    sys.exit(1)

with open(gameplay_c_path, "r", encoding="utf-8") as f:
    gameplay_c_content = f.read()

gc_search_1 = """JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeBallRetentionShieldCompute(
    JNIEnv *env,
    jclass clazz,
    jboolean hasBall,
    jboolean trusted,
    jfloat confidence,
    jfloat ballX,
    jfloat ballY,
    jint viableLaneCount,
    jfloat passTargetX,
    jfloat passTargetY,
    jint zonesLeftTheirs,
    jint zonesMidTheirs,
    jint zonesRightTheirs,
    jfloat defenderDensity,
    jint laneCount,
    jfloatArray resultBuffer
) {
    (void)clazz;

    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);"""

gc_replace_1 = """JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeBallRetentionShieldCompute(
    JNIEnv *env,
    jclass clazz,
    jboolean hasBall,
    jboolean trusted,
    jfloat confidence,
    jfloat ballX,
    jfloat ballY,
    jint viableLaneCount,
    jfloat passTargetX,
    jfloat passTargetY,
    jint zonesLeftTheirs,
    jint zonesMidTheirs,
    jint zonesRightTheirs,
    jfloat defenderDensity,
    jint laneCount,
    jfloatArray resultBuffer
) {
    (void)clazz;

    if (!resultBuffer || (*env)->GetArrayLength(env, resultBuffer) < 5) return;

    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);"""

gc_search_2 = """JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeInstantInterceptCompute(
    JNIEnv *env,
    jclass clazz,
    jboolean hasBall,
    jboolean trusted,
    jfloat confidence,
    jboolean ownerHasOwner,
    jfloat ownerX,
    jfloat ownerY,
    jfloat ownerVx,
    jfloat ownerVy,
    jboolean ownerIsUserTeam,
    jfloat ballX,
    jfloat ballY,
    jfloatArray resultBuffer
) {
    (void)clazz;

    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);"""

gc_replace_2 = """JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeInstantInterceptCompute(
    JNIEnv *env,
    jclass clazz,
    jboolean hasBall,
    jboolean trusted,
    jfloat confidence,
    jboolean ownerHasOwner,
    jfloat ownerX,
    jfloat ownerY,
    jfloat ownerVx,
    jfloat ownerVy,
    jboolean ownerIsUserTeam,
    jfloat ballX,
    jfloat ballY,
    jfloatArray resultBuffer
) {
    (void)clazz;

    if (!resultBuffer || (*env)->GetArrayLength(env, resultBuffer) < 5) return;

    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);"""

gc_search_3 = """JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeBuildUpPressCompute(
    JNIEnv *env,
    jclass clazz,
    jboolean hasBall,
    jboolean trusted,
    jfloat confidence,
    jboolean ownerHasOwner,
    jfloat ownerX,
    jfloat ownerY,
    jboolean ownerIsUserTeam,
    jfloat ballX,
    jfloat ballY,
    jfloatArray resultBuffer
) {
    (void)clazz;

    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);"""

gc_replace_3 = """JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeBuildUpPressCompute(
    JNIEnv *env,
    jclass clazz,
    jboolean hasBall,
    jboolean trusted,
    jfloat confidence,
    jboolean ownerHasOwner,
    jfloat ownerX,
    jfloat ownerY,
    jboolean ownerIsUserTeam,
    jfloat ballX,
    jfloat ballY,
    jfloatArray resultBuffer
) {
    (void)clazz;

    if (!resultBuffer || (*env)->GetArrayLength(env, resultBuffer) < 4) return;

    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);"""

if "GetArrayLength(env, resultBuffer) < 5" in gameplay_c_content:
    print("native_gameplay_compute.c: Already patched.")
elif gc_search_1 in gameplay_c_content:
    gameplay_c_content = gameplay_c_content.replace(gc_search_1, gc_replace_1)
    gameplay_c_content = gameplay_c_content.replace(gc_search_2, gc_replace_2)
    gameplay_c_content = gameplay_c_content.replace(gc_search_3, gc_replace_3)
    with open(gameplay_c_path, "w", encoding="utf-8") as f:
        f.write(gameplay_c_content)
    print("native_gameplay_compute.c: Successfully patched.")
else:
    print("FAIL: Expected pattern not found in native_gameplay_compute.c")
    sys.exit(1)

# Target 3: app/src/main/java/com/assistant/gameplay_engine.kt
engine_path = os.path.join("app", "src", "main", "java", "com", "assistant", "gameplay_engine.kt")
if not os.path.exists(engine_path):
    print(f"FAIL: File not found: {engine_path}")
    sys.exit(1)

with open(engine_path, "r", encoding="utf-8") as f:
    engine_content = f.read()

ge_search_1 = """/* ========
AgilityContributor
======== */
object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    // ZERO-ALLOCATION JNI BUFFER
    private val nativeAgilityOut = FloatArray(4) // [targetX, targetY, authority, confidence]

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // UNCONDITIONAL AGGRESSIVE EXECUTION: Removed all Kotlin math/state extraction

        // JNI TINY ADAPTER: Delegate heavy physics to native C ABI
        val success = com.assistant.NativeBridge.nativeComputeAgilityPhysics(
            frame.ballX, frame.ballY,
            frame.passTargetX, frame.passTargetY,
            frame.defenderDensity,
            nativeAgilityOut
        )

        if (!success || nativeAgilityOut[2] < 0.1f) return null // Native layer determined no viable agile move

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = nativeAgilityOut[0].coerceAtLeast(0f),
            targetY = nativeAgilityOut[1].coerceAtLeast(0f),
            authority = nativeAgilityOut[2],
            confidence = nativeAgilityOut[3],
            durationHintMs = 20L // Ultra-fast transition
        )
    }
}"""

ge_replace_1 = """/* ========
AgilityContributor
======== */
object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    // ZERO-ALLOCATION JNI BUFFER: 6 floats produced by native C ABI
    // [0]=stabilityBoost, [1]=controlRetentionBoost, [2]=turnAssist, [3]=shieldAngle, [4]=shieldDuration, [5]=shieldActive
    private val nativeAgilityOut = FloatArray(6)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // UNCONDITIONAL AGGRESSIVE EXECUTION: Delegate heavy physics to native C ABI
        val success = com.assistant.NativeBridge.nativeComputeAgilityPhysics(
            frame.ballX, frame.ballY,
            frame.passTargetX, frame.passTargetY,
            frame.defenderDensity,
            nativeAgilityOut
        )

        if (!success || nativeAgilityOut[5] < 0.1f) return null // Native layer determined no active shield/agile move

        val angleRad = Math.toRadians(nativeAgilityOut[3].toDouble())
        val dist = nativeAgilityOut[0] * 10.0f
        val calculatedTargetX = (frame.ballX + kotlin.math.cos(angleRad) * dist).toFloat().coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
        val calculatedTargetY = (frame.ballY + kotlin.math.sin(angleRad) * dist).toFloat().coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())
        val authority = (nativeAgilityOut[0] / 15.0f).coerceIn(0.1f, 0.95f)
        val confidence = nativeAgilityOut[1].coerceIn(0.1f, 1.0f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = calculatedTargetX,
            targetY = calculatedTargetY,
            authority = authority,
            confidence = confidence,
            durationHintMs = 20L // Ultra-fast transition
        )
    }
}"""

ge_search_2 = """/* ========
BallRetentionShieldContributor
======== */
object BallRetentionShieldContributor : GameplayContributor {
    override val engineName   = "BallRetentionShield"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)

    // ZERO-ALLOCATION JNI BUFFER
    private val nativeBuffer = FloatArray(4) // [targetX, targetY, authority, confidence]

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // UNCONDITIONAL AGGRESSIVE EXECUTION: Always evaluate shield positioning

        // JNI TINY ADAPTER: Direct C ABI call, zero JVM allocation overhead
        val success = com.assistant.NativeBridge.nativeComputeBallRetention(
            frame.ballX, frame.ballY,
            frame.defenderDensity,
            nativeBuffer
        )

        if (!success || nativeBuffer[2] < 0.1f) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = nativeBuffer[0].coerceAtLeast(0f),
            targetY = nativeBuffer[1].coerceAtLeast(0f),
            authority = nativeBuffer[2],
            confidence = nativeBuffer[3],
            durationHintMs = 15L // Maximum reaction speed
        )
    }
}"""

ge_replace_2 = """/* ========
BallRetentionShieldContributor
======== */
object BallRetentionShieldContributor : GameplayContributor {
    override val engineName   = "BallRetentionShield"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)

    // ZERO-ALLOCATION JNI BUFFER: 5 floats produced by native C ABI
    // [0]=active, [1]=shieldX, [2]=shieldY, [3]=authority, [4]=risk
    private val nativeBuffer = FloatArray(5)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // UNCONDITIONAL AGGRESSIVE EXECUTION: Always evaluate shield positioning
        val success = com.assistant.NativeBridge.nativeComputeBallRetention(
            frame.ballX, frame.ballY,
            frame.defenderDensity,
            nativeBuffer
        )

        if (!success || nativeBuffer[0] < 0.1f) return null // Active flag check

        val authority = nativeBuffer[3].coerceIn(0.1f, 1.0f)
        val confidence = (1.0f - nativeBuffer[4]).coerceIn(0.1f, 1.0f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = nativeBuffer[1].coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback()),
            targetY = nativeBuffer[2].coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback()),
            authority = authority,
            confidence = confidence,
            durationHintMs = 15L // Maximum reaction speed
        )
    }
}"""

if "nativeAgilityOut = FloatArray(6)" in engine_content and "nativeBuffer = FloatArray(5)" in engine_content:
    print("gameplay_engine.kt: Already patched.")
elif ge_search_1 in engine_content and ge_search_2 in engine_content:
    engine_content = engine_content.replace(ge_search_1, ge_replace_1)
    engine_content = engine_content.replace(ge_search_2, ge_replace_2)
    with open(engine_path, "w", encoding="utf-8") as f:
        f.write(engine_content)
    print("gameplay_engine.kt: Successfully patched.")
else:
    print("FAIL: Expected pattern not found in gameplay_engine.kt")
    sys.exit(1)

# Verification
with open(agility_c_path, "r", encoding="utf-8") as f:
    if "GetArrayLength(env, outBuffer) < 6" not in f.read():
        print("FAIL: Verification failed for native_agility_physics.c")
        sys.exit(1)

with open(gameplay_c_path, "r", encoding="utf-8") as f:
    if "GetArrayLength(env, resultBuffer) < 5" not in f.read():
        print("FAIL: Verification failed for native_gameplay_compute.c")
        sys.exit(1)

with open(engine_path, "r", encoding="utf-8") as f:
    check_ge = f.read()
    if "nativeAgilityOut = FloatArray(6)" not in check_ge or "nativeBuffer = FloatArray(5)" not in check_ge:
        print("FAIL: Verification failed for gameplay_engine.kt")
        sys.exit(1)

print("PASS: All mutations verified successfully.")

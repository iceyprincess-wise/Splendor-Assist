import os
import re
import sys

REPO_ROOT = os.environ.get("REPO_ROOT", os.path.expanduser("~/projects/Splendor-Assist"))
if not os.path.exists(REPO_ROOT):
    REPO_ROOT = "/data/data/com.termux/files/home/projects/Splendor-Assist"
if not os.path.exists(REPO_ROOT):
    print("FATAL: Repository root not found. Set REPO_ROOT env var or run from ~/projects/Splendor-Assist")
    sys.exit(1)

def write_file(path, content):
    full_path = os.path.join(REPO_ROOT, path)
    os.makedirs(os.path.dirname(full_path), exist_ok=True)
    with open(full_path, "w") as f:
        f.write(content)
    print(f"SUCCESS: Overwrote {path}")

def replace_in_file(filepath, old_content, new_content):
    full_path = os.path.join(REPO_ROOT, filepath)
    if not os.path.exists(full_path):
        print(f"ERROR: File not found: {full_path}")
        return False
    with open(full_path, "r") as f:
        content = f.read()
    if old_content not in content:
        print(f"ERROR: Anchor not found in {filepath}. Attempting regex fallback...")
        return False
    content = content.replace(old_content, new_content)
    with open(full_path, "w") as f:
        f.write(content)
    print(f"SUCCESS: Patched {filepath}")
    return True

# 1. OVERWRITE native_dominance_matrix.c
write_file("app/src/main/cpp/native_dominance_matrix.c", """#include <jni.h>
#include <math.h>
#include <stdint.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

static inline float fast_inv_sqrt(float x) {
    float xhalf = 0.5f * x;
    union { int i; float f; } u;
    u.f = x;
    u.i = 0x5f3759df - (u.i >> 1);
    u.f = u.f * (1.5f - xhalf * u.f * u.f);
    return u.f;
}

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeComputeDominanceVectors(
        JNIEnv* env, jobject thiz,
        jfloat defX, jfloat defY, jfloat defVx, jfloat defVy,
        jfloat targetX, jfloat targetY, jfloat targetVx, jfloat targetVy,
        jboolean isHoldingPressure, jfloat screenWidth, jfloat screenHeight,
        jint globalMatchTick, jfloatArray outBuffer) {

    jfloat* result = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!result) return;

    float overrideJoystickX = 250.0f;
    float overrideJoystickY = 550.0f;
    float executeTackleTap = 0.0f;
    float gestureDuration = 42.0f;

    float dx = targetX - defX;
    float dy = targetY - defY;
    float dMagSq = dx * dx + dy * dy;
    float invDist = fast_inv_sqrt(dMagSq);
    float distance = (dMagSq > 0.0f) ? (1.0f / invDist) : 0.0f;

    float holdMask = (float)isHoldingPressure;
    float distMask = (distance > 0.1f) ? 1.0f : 0.0f;
    float activeMask = holdMask * distMask;

    float leadScale = 0.12f; 
    float predictedTargetX = targetX + (targetVx * leadScale);
    float predictedTargetY = targetY + (targetVy * leadScale);

    float pdx = predictedTargetX - defX;
    float pdy = predictedTargetY - defY;
    float pMagSq = pdx * pdx + pdy * pdy;
    float invPDist = fast_inv_sqrt(pMagSq);

    float dirX = (pMagSq > 0.0f) ? (pdx * invPDist) : 0.0f;
    float dirY = (pMagSq > 0.0f) ? (pdy * invPDist) : 0.0f;

    // 🚨 OVERDRIVE INJECTION: Micro-Strobe Target Pulsing
    float strobeMask = (float)(globalMatchTick & 1);
    float pulseMag = 90.0f + (strobeMask * 35.0f); // Alternates 90.0f / 125.0f

    float strobeX = 250.0f + (dirX * pulseMag);
    float strobeY = 550.0f + (dirY * pulseMag);

    overrideJoystickX = overrideJoystickX * (1.0f - activeMask) + strobeX * activeMask;
    overrideJoystickY = overrideJoystickY * (1.0f - activeMask) + strobeY * activeMask;

    // Close-quarter combat automation triggers
    float closeThreshold = screenHeight * 0.075f;
    float closeMask = (distance < closeThreshold && distance > 0.0f) ? 1.0f : 0.0f;
    
    float tacklePulse = (float)((globalMatchTick & 1) == 0); 
    executeTackleTap = executeTackleTap * (1.0f - closeMask) + tacklePulse * closeMask;
    gestureDuration = gestureDuration * (1.0f - closeMask) + 10.0f * closeMask;
    
    overrideJoystickX += (dirX * 35.0f * closeMask);
    overrideJoystickY += (dirY * 35.0f * closeMask);

    result[0] = branchless_coerce(overrideJoystickX, 0.0f, screenWidth);
    result[1] = branchless_coerce(overrideJoystickY, 0.0f, screenHeight);
    result[2] = executeTackleTap;
    result[3] = gestureDuration;

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}
""")

# 2. OVERWRITE native_counterattack.c (Removed hard 0.60 gate)
write_file("app/src/main/cpp/native_counterattack.c", """#include <jni.h>
#include <stdint.h>
#include <math.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeCounterattackAnalyze(
    JNIEnv* env, jobject thiz, jint attackers, jboolean teamShapeFound, jfloat teamShapeConf,
    jboolean offensiveLineFound, jfloat offensiveLineConf, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    
    float shapeMask = (float)teamShapeFound;
    float lineMask = (float)offensiveLineFound;
    
    float conf = (attackers * 0.090909f) + 
                 (shapeMask * 0.15f) + 
                 (lineMask * 0.15f) + 
                 (teamShapeConf * 0.05f) + 
                 (offensiveLineConf * 0.05f);
                 
    conf = branchless_coerce(conf, 0.0f, 1.0f);
    
    // NO HARD GATES. Unconditional aggressive output via smoothstep amplification
    float amplifiedConf = conf * conf * (3.0f - 2.0f * conf); 
    
    r[0] = amplifiedConf; 
    r[1] = conf;
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}
""")

# 3. OVERWRITE native_input_strobe.c (Upgraded to GetPrimitiveArrayCritical + Branchless)
write_file("app/src/main/cpp/native_input_strobe.c", """#include <jni.h>
#include <stdint.h>
#include <math.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeInjectStrobePackets(
    JNIEnv* env, jclass clazz,
    jfloat startX, jfloat startY, jfloat endX, jfloat endY,
    jint durationMs, jint pointerCount, jfloatArray outPackets) {
    
    jfloat* packets = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outPackets, NULL);
    if (!packets) return;

    int packetIdx = 0;
    int stride = durationMs / (pointerCount > 0 ? pointerCount : 1);
    if (stride < 1) stride = 1;
    
    float invDuration = (durationMs > 0) ? (1.0f / (float)durationMs) : 0.0f;
    float dx = endX - startX;
    float dy = endY - startY;

    for (int i = 0; i < pointerCount; i++) {
        int t = i * stride;
        float progress = (float)t * invDuration;
        progress = branchless_coerce(progress, 0.0f, 1.0f);
        
        float x = startX + dx * progress;
        float y = startY + dy * progress;

        packets[packetIdx++] = (float)i;
        packets[packetIdx++] = x;
        packets[packetIdx++] = y;
        packets[packetIdx++] = (float)t;
    }

    (*env)->ReleasePrimitiveArrayCritical(env, outPackets, packets, 0);
}
""")

# 4. OVERWRITE native_stopper_matrix.c (Unconditional blending)
write_file("app/src/main/cpp/native_stopper_matrix.c", """#include <jni.h>
#include <math.h>
#include <stdint.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

static inline float fast_inv_sqrt(float x) {
    float xhalf = 0.5f * x;
    union { int i; float f; } u;
    u.f = x;
    u.i = 0x5f3759df - (u.i >> 1);
    u.f = u.f * (1.5f - xhalf * u.f * u.f);
    return u.f;
}

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeExecuteStopperMatrix(
        JNIEnv* env, jobject thiz,
        jfloat playerX, jfloat playerY, jfloat playerVx, jfloat playerVy,
        jfloat homeAnchorX, jfloat homeAnchorY,
        jfloat oppX, jfloat oppY, jfloat oppVx, jfloat oppVy,
        jboolean isHoldingPressure, jfloat screenWidth, jfloat screenHeight,
        jfloatArray outBuffer) {
    jfloat* result = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!result) return;

    float targetStickX = 250.0f;
    float targetStickY = 550.0f;
    float forcePhysicalBarge = 0.0f;
    float trackingPacingDuration = 40.0f;

    float dxAnchor = homeAnchorX - playerX;
    float dyAnchor = homeAnchorY - playerY;
    float anchorDistSq = dxAnchor * dxAnchor + dyAnchor * dyAnchor;
    float invAnchorDist = fast_inv_sqrt(anchorDistSq);
    float distanceToAnchor = (anchorDistSq > 0.0f) ? (1.0f / invAnchorDist) : 0.0f;

    float dxOpp = oppX - playerX;
    float dyOpp = oppY - playerY;
    float oppDistSq = dxOpp * dxOpp + dyOpp * dyOpp;
    float invOppDist = fast_inv_sqrt(oppDistSq);
    float distanceToOpponent = (oppDistSq > 0.0f) ? (1.0f / invOppDist) : 0.0f;

    float closeCombatThreshold = screenHeight * 0.065f;
    float intermediateThreatZone = screenHeight * 0.18f;
    
    float dirAnchorX = (anchorDistSq > 0.0f) ? (dxAnchor * invAnchorDist) : 0.0f;
    float dirAnchorY = (anchorDistSq > 0.0f) ? (dyAnchor * invAnchorDist) : 0.0f;
    
    float anchorWeight = branchless_coerce((distanceToAnchor - (screenWidth * 0.22f)) * 0.01f, 0.0f, 1.0f);
    targetStickX += dirAnchorX * 100.0f * anchorWeight;
    targetStickY += dirAnchorY * 100.0f * anchorWeight;
    
    float holdMask = (float)isHoldingPressure;
    float oppLeadScale = 0.050f;
    float predOppX = oppX + (oppVx * oppLeadScale);
    float predOppY = oppY + (oppVy * oppLeadScale);
    float pdx = predOppX - playerX;
    float pdy = predOppY - playerY;
    float pDistSq = pdx * pdx + pdy * pdy;
    float invPDist = fast_inv_sqrt(pDistSq);
    float dirOppX = (pDistSq > 0.0f) ? (pdx * invPDist) : 0.0f;
    float dirOppY = (pDistSq > 0.0f) ? (pdy * invPDist) : 0.0f;

    float oppWeight = holdMask * branchless_coerce((intermediateThreatZone - distanceToOpponent) * 0.01f, 0.0f, 1.0f);
    targetStickX += dirOppX * 100.0f * oppWeight;
    targetStickY += dirOppY * 100.0f * oppWeight;

    float closeMask = branchless_coerce((closeCombatThreshold - distanceToOpponent) * 0.05f, 0.0f, 1.0f) * holdMask;
    forcePhysicalBarge = closeMask;
    trackingPacingDuration = 40.0f - (28.0f * closeMask); 
    targetStickX += dirOppX * 30.0f * closeMask;
    targetStickY += dirOppY * 30.0f * closeMask;

    result[0] = branchless_coerce(targetStickX, 0.0f, screenWidth);
    result[1] = branchless_coerce(targetStickY, 0.0f, screenHeight);
    result[2] = forcePhysicalBarge;
    result[3] = trackingPacingDuration;
    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}
""")

# 5. OVERWRITE native_threat_priority.c (Branchless multipliers)
write_file("app/src/main/cpp/native_threat_priority.c", """#include <jni.h>
#include <math.h>
#include <stdint.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeThreatPriorityCompute(
    JNIEnv *env, jclass clazz, jint threatScore, jint zone, jint direction,
    jint awareness, jint prediction, jint interceptionBonus, jint recoveryBonus,
    jint x, jint y, jint width, jint height, jfloatArray resultBuffer
) {
    (void)clazz;
    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);
    if (!res) return;

    float dir1Mask = (direction == 1) ? 1.0f : 0.0f;
    float dir2Mask = (direction == 2) ? 1.0f : 0.0f;
    float dirBonus = (dir1Mask * 35.0f) + (dir2Mask * 30.0f);

    float zone1Mask = (zone == 1) ? 1.0f : 0.0f;
    float zone2Mask = (zone == 2) ? 1.0f : 0.0f;
    float zone3Mask = (zone == 3) ? 1.0f : 0.0f;
    float zoneBonus = (zone1Mask * 40.0f) + (zone2Mask * 25.0f) + (zone3Mask * 10.0f);

    jint priority = threatScore + (jint)dirBonus + (awareness >> 1) + (prediction >> 1) + interceptionBonus + recoveryBonus + (jint)zoneBonus;

    float invHeight = (height > 0) ? (1.0f / (float)height) : 0.0f;
    float invWidth = (width > 0) ? (1.0f / (float)width) : 0.0f;
    
    float ny = (float)y * invHeight;
    float nx = (float)x * invWidth;
    
    float highMask = branchless_coerce((ny - 0.80f) * 10.0f, 0.0f, 1.0f);
    float lowMask = branchless_coerce((0.40f - ny) * 10.0f, 0.0f, 1.0f);
    float midMask = (1.0f - highMask) * (1.0f - lowMask);
    
    jint heightBand = (jint)(highMask * 2.0f + midMask * 1.0f + lowMask * 0.0f);

    res[0] = (float)priority;
    res[1] = (float)heightBand;
    res[2] = branchless_coerce(nx, 0.0f, 1.0f);

    (*env)->ReleasePrimitiveArrayCritical(env, resultBuffer, res, 0);
}
""")

# 6. OVERWRITE native_agility_physics.c (Continuous branchless scaling)
write_file("app/src/main/cpp/native_agility_physics.c", """#include <jni.h>
#include <math.h>
#include <stdint.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

static inline float fast_atan2f(float y, float x) {
    float abs_y = fabsf(y);
    float abs_x = fabsf(x);
    float min_val = (abs_x < abs_y) ? abs_x : abs_y;
    float max_val = (abs_x > abs_y) ? abs_x : abs_y;
    float safe_max = (max_val > 0.0001f) ? max_val : 0.0001f;
    
    float r = min_val / safe_max;
    float r2 = r * r;
    float angle = (((-0.04649647f * r2 + 0.15931422f) * r2 - 0.32762281f) * r2 + 0.9998660f) * r;
    
    float crossMask = (abs_y > abs_x) ? 1.0f : 0.0f;
    angle = angle * (1.0f - crossMask) + ((M_PI / 2.0f) - angle) * crossMask;
    
    float negXMask = (x < 0.0f) ? 1.0f : 0.0f;
    angle = angle * (1.0f - negXMask) + (M_PI - angle) * negXMask;
    
    float negYMask = (y < 0.0f) ? 1.0f : 0.0f;
    angle = angle * (1.0f - negYMask) + (-angle) * negYMask;
    
    return angle;
}

static inline float fast_wrap_360(float angle) {
    float q = floorf(angle * 0.002777778f);
    angle = angle - q * 360.0f;
    float overMask = (angle >= 360.0f) ? 1.0f : 0.0f;
    float underMask = (angle < 0.0f) ? 1.0f : 0.0f;
    angle = angle - (overMask * 360.0f) + (underMask * 360.0f);
    return angle;
}

static inline uint32_t local_xorshift32(uint32_t* state) {
    uint32_t x = *state;
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    *state = x;
    return x;
}

static inline float local_next_float(uint32_t* state) {
    return (float)(local_xorshift32(state) & 0xFFFFFF) * 5.960464477539063e-8f;
}

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl(
        JNIEnv* env, jclass clazz,
        jfloat playerVelocity, jfloat opponentDistance,
        jfloat movementAngleDegrees, jfloat possessionConfidence,
        jfloat turnIntensity, jfloat playerX, jfloat playerY,
        jfloat oppX, jfloat oppY, jint threadSeed, jfloatArray outBuffer) {

    jfloat* result = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!result) return;

    uint32_t rng_state = (uint32_t)threadSeed ^ 2463534242U;

    float r_fuzz1 = local_next_float(&rng_state);
    float r_fuzz2 = local_next_float(&rng_state);
    float r_fuzz3 = local_next_float(&rng_state);

    float normDist = opponentDistance * 0.01f;
    float upperFuzz = 2.2f + (r_fuzz1 * 0.04f - 0.02f);
    float lowerFuzz = 1.0f + (r_fuzz2 * 0.02f - 0.01f);
    
    float velMask = branchless_coerce(playerVelocity * 10.0f, 0.0f, 1.0f);
    float closeProxMask = branchless_coerce((lowerFuzz - normDist) * 2.0f, 0.0f, 1.0f);
    float medProxMask = branchless_coerce((upperFuzz - normDist) * 2.0f, 0.0f, 1.0f);
    
    float shieldActive = medProxMask * (velMask + closeProxMask - velMask * closeProxMask);
    shieldActive = branchless_coerce(shieldActive, 0.0f, 1.0f);

    float proximity = 1.0f - (opponentDistance * 0.004545455f);
    proximity = branchless_coerce(proximity, 0.0f, 1.0f);

    float speed = playerVelocity * 0.06666667f;
    speed = branchless_coerce(speed, 0.0f, 1.0f);

    float conf = branchless_coerce(possessionConfidence, 0.0f, 1.0f);

    float shieldBoost = 4.0f + proximity * 6.0f + speed * 3.0f + conf * 2.0f;
    float softP = 1.0f - opponentDistance * 0.002f;
    softP = branchless_coerce(softP, 0.0f, 1.0f);
    float distBoost = 3.0f + softP * 4.0f * conf;
    float baseBoost = 1.5f + conf * 1.5f;
    
    float stabilityBoost = shieldBoost * shieldActive + distBoost * (1.0f - shieldActive) * medProxMask + baseBoost * (1.0f - medProxMask);
    stabilityBoost = branchless_coerce(stabilityBoost, 1.5f, 15.0f);

    float controlRetentionBoost = conf * 0.6f + proximity * 0.4f;
    controlRetentionBoost = branchless_coerce(controlRetentionBoost, 0.0f, 1.0f);

    float turnBase = turnIntensity * 0.7f + proximity * 0.3f;
    float turnConf = (conf > 0.3f) ? conf : 0.3f;
    float turnAssist = turnBase * turnConf;
    turnAssist = branchless_coerce(turnAssist, 0.0f, 1.0f);

    float angleDeg = fast_atan2f(oppY - playerY, oppX - playerX) * 57.29578f;
    float shieldAngle = fast_wrap_360(angleDeg + 180.0f + (r_fuzz3 * 1.2f - 0.6f));

    float pBonus = 100.0f / (normDist > 0.5f ? normDist : 0.5f);
    pBonus = branchless_coerce(pBonus, 0.0f, 60.0f);
    float vBonus = playerVelocity * 10.0f;
    vBonus = branchless_coerce(vBonus, 0.0f, 15.0f);
    
    int32_t rand_mod = ((int32_t)(local_xorshift32(&rng_state) % 7)) - 3;
    float shieldDuration = 45.0f + pBonus + vBonus + (float)rand_mod;
    shieldDuration = branchless_coerce(shieldDuration, 40.0f, 124.0f);

    result[0] = stabilityBoost;
    result[1] = controlRetentionBoost;
    result[2] = turnAssist;
    result[3] = shieldAngle;
    result[4] = shieldDuration;
    result[5] = shieldActive;

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}
""")

# 7. PATCH NativeBridge.kt (Add globalMatchTick parameter)
nb_old = "@JvmStatic external fun nativeComputeDominanceVectors(defX: Float, defY: Float, defVx: Float, defVy: Float, targetX: Float, targetY: Float, targetVx: Float, targetVy: Float, isHoldingPressure: Boolean, screenWidth: Float, screenHeight: Float, outBuffer: FloatArray)"
nb_new = "@JvmStatic external fun nativeComputeDominanceVectors(defX: Float, defY: Float, defVx: Float, defVy: Float, targetX: Float, targetY: Float, targetVx: Float, targetVy: Float, isHoldingPressure: Boolean, screenWidth: Float, screenHeight: Float, globalMatchTick: Int, outBuffer: FloatArray)"
replace_in_file("app/src/main/java/com/assistant/NativeBridge.kt", nb_old, nb_new)

# 8. PATCH gameplay_engine.kt (Inject globalMatchTick into caller)
ge_path = os.path.join(REPO_ROOT, "app/src/main/java/com/assistant/gameplay_engine.kt")
if os.path.exists(ge_path):
    with open(ge_path, "r") as f:
        content = f.read()
    
    pattern = r'(nativeComputeDominanceVectors\s*\([^)]*?screenHeight\s*,\s*)(outBuffer\s*\))'
    new_content = re.sub(pattern, r'\1SystemClock.uptimeMillis().toInt(), \2', content, flags=re.DOTALL)
    
    if new_content != content:
        with open(ge_path, "w") as f:
            f.write(new_content)
        print("SUCCESS: Patched caller in gameplay_engine.kt")
    else:
        print("WARNING: Could not find nativeComputeDominanceVectors caller in gameplay_engine.kt to patch.")

print("\nPATCH COMPLETE. Run `./gradlew build` to compile the upgraded .so library.")

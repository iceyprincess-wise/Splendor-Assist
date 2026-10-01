#include <jni.h>
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

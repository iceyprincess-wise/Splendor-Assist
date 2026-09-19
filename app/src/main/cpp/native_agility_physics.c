#include <jni.h>
#include <math.h>
#include <stdint.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

static inline float fast_atan2f(float y, float x) {
    if (x == 0.0f && y == 0.0f) return 0.0f;
    float abs_y = fabsf(y);
    float abs_x = fabsf(x);
    float min_val = (abs_x < abs_y) ? abs_x : abs_y;
    float max_val = (abs_x > abs_y) ? abs_x : abs_y;
    if (max_val == 0.0f) return 0.0f;
    
    float r = min_val / max_val;
    float r2 = r * r;
    float angle = (((-0.04649647f * r2 + 0.15931422f) * r2 - 0.32762281f) * r2 + 0.9998660f) * r;
    
    if (abs_y > abs_x) angle = (M_PI / 2.0f) - angle;
    if (x < 0.0f) angle = M_PI - angle;
    if (y < 0.0f) angle = -angle;
    return angle;
}

static inline float fast_wrap_360(float angle) {
    if (angle >= 360.0f) {
        angle -= ((int)(angle * 0.002777778f)) * 360.0f;
    } else if (angle < 0.0f) {
        angle += ((int)(-angle * 0.002777778f) + 1) * 360.0f;
    }
    return (angle >= 360.0f) ? angle - 360.0f : angle;
}

static inline uint32_t local_xorshift32(uint32_t* state) {
    uint32_t x = *state;
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    *state = x;
    return x;
}

static inline float local_next_float(uint32_t* state) {
    return (float)(local_xorshift32(state) & 0xFFFFFF) / (float)0x1000000;
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

    float shieldActive = 0.0f;
    if (opponentDistance > 0.0f) {
        float normDist = opponentDistance * 0.01f;
        float upperFuzz = 2.2f + (r_fuzz1 * 0.04f - 0.02f);
        float lowerFuzz = 1.0f + (r_fuzz2 * 0.02f - 0.01f);
        if (normDist < upperFuzz && (playerVelocity > 0.15f || normDist < lowerFuzz)) {
            shieldActive = 1.0f;
        }
    }

    float proximity = 1.0f - (opponentDistance * 0.004545455f);
    proximity = (proximity < 0.0f) ? 0.0f : ((proximity > 1.0f) ? 1.0f : proximity);

    float speed = playerVelocity * 0.06666667f;
    speed = (speed < 0.0f) ? 0.0f : ((speed > 1.0f) ? 1.0f : speed);

    float conf = (possessionConfidence < 0.0f) ? 0.0f : ((possessionConfidence > 1.0f) ? 1.0f : possessionConfidence);

    float stabilityBoost;
    if (shieldActive > 0.5f) {
        stabilityBoost = 4.0f + proximity * 6.0f + speed * 3.0f + conf * 2.0f;
    } else if (opponentDistance >= 1.0f && opponentDistance <= 500.0f) {
        float softP = 1.0f - opponentDistance * 0.002f;
        softP = (softP < 0.0f) ? 0.0f : ((softP > 1.0f) ? 1.0f : softP);
        stabilityBoost = 3.0f + softP * 4.0f * conf;
    } else {
        stabilityBoost = (conf > 0.0f) ? 3.0f : 1.5f;
    }
    stabilityBoost = (stabilityBoost < 1.5f) ? 1.5f : ((stabilityBoost > 15.0f) ? 15.0f : stabilityBoost);

    float controlRetentionBoost = (conf > 0.05f) ? (conf * 0.6f + proximity * 0.4f) : (proximity * 0.5f);
    controlRetentionBoost = (controlRetentionBoost < 0.0f) ? 0.0f : ((controlRetentionBoost > 1.0f) ? 1.0f : controlRetentionBoost);

    float turnAssist = (turnIntensity > 0.05f) ? ((turnIntensity * 0.7f + proximity * 0.3f) * ((conf > 0.3f) ? conf : 0.3f)) : 0.0f;
    turnAssist = (turnAssist < 0.0f) ? 0.0f : ((turnAssist > 1.0f) ? 1.0f : turnAssist);

    float shieldAngle;
    if (playerX == playerX && playerY == playerY && oppX == oppX && oppY == oppY) {
        float angleDeg = fast_atan2f(oppY - playerY, oppX - playerX) * 57.29578f;
        shieldAngle = fast_wrap_360(angleDeg + 180.0f + (r_fuzz3 * 1.2f - 0.6f));
    } else {
        float angle = movementAngleDegrees;
        if (angle > 180.0f || angle < -180.0f) {
            float q = floorf((angle + 180.0f) * 0.002777778f);
            angle = angle - q * 360.0f;
        }
        float offsetBase = (angle >= 0.0f) ? angle + 90.0f : angle - 90.0f;
        shieldAngle = fast_wrap_360(offsetBase + (r_fuzz3 * 1.3f - 0.65f));
    }

    float shieldDuration;
    if (opponentDistance <= 0.0f) {
        int32_t rand_mod = ((int32_t)(local_xorshift32(&rng_state) % 5)) - 2;
        shieldDuration = 45.0f + (float)rand_mod;
    } else {
        float normDist = opponentDistance * 0.01f;
        if (normDist < 0.5f) normDist = 0.5f;
        float pBonus = 100.0f / normDist;
        if (pBonus > 60.0f) pBonus = 60.0f;
        float vBonus = playerVelocity * 10.0f;
        if (vBonus > 15.0f) vBonus = 15.0f;
        
        int32_t rand_mod = ((int32_t)(local_xorshift32(&rng_state) % 7)) - 3;
        shieldDuration = 45.0f + pBonus + vBonus + (float)rand_mod;
    }
    shieldDuration = (shieldDuration < 40.0f) ? 40.0f : ((shieldDuration > 124.0f) ? 124.0f : shieldDuration);

    result[0] = stabilityBoost;
    result[1] = controlRetentionBoost;
    result[2] = turnAssist;
    result[3] = shieldAngle;
    result[4] = shieldDuration;
    result[5] = shieldActive;

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}

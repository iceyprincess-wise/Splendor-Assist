#include <jni.h>
#include <math.h>
#include <android/log.h>
#include <stdint.h>

#define LOG_TAG "NativeAgilityPhysics"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static uint32_t xorshift_state = 2463534242U;

static inline uint32_t xorshift32() {
    uint32_t x = xorshift_state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    xorshift_state = x;
    return x;
}

static inline float next_float() {
    return (float)(xorshift32() & 0xFFFFFF) / (float)0x1000000;
}

static inline int32_t next_int(int32_t min, int32_t max) {
    if (min >= max) return min;
    uint32_t range = (uint32_t)(max - min);
    return min + (int32_t)(xorshift32() % range);
}

JNIEXPORT void JNICALL
Java_com_assistant_AgilityEngine_nativeComputeAgility(
        JNIEnv* env, jobject thiz,
        jfloat playerVelocity, jfloat opponentDistance,
        jfloat movementAngleDegrees, jfloat possessionConfidence,
        jfloat turnIntensity, jfloat playerX, jfloat playerY,
        jfloat oppX, jfloat oppY, jfloatArray outBuffer) {

    jfloat result[6];

    jboolean shieldActive = JNI_FALSE;
    if (opponentDistance > 0.0f) {
        float normalizedDistance = opponentDistance / 100.0f;
        float upperTriggerFuzz = 2.2f + (next_float() * 0.04f - 0.02f);
        float lowerOverrideFuzz = 1.0f + (next_float() * 0.02f - 0.01f);
        if (normalizedDistance < upperTriggerFuzz && (playerVelocity > 0.15f || normalizedDistance < lowerOverrideFuzz)) {
            shieldActive = JNI_TRUE;
        }
    }

    float proximity = 1.0f - (opponentDistance / 220.0f);
    if (proximity < 0.0f) proximity = 0.0f;
    if (proximity > 1.0f) proximity = 1.0f;

    float speed = playerVelocity / 15.0f;
    if (speed < 0.0f) speed = 0.0f;
    if (speed > 1.0f) speed = 1.0f;

    float confidence = possessionConfidence;
    if (confidence < 0.0f) confidence = 0.0f;
    if (confidence > 1.0f) confidence = 1.0f;

    float stabilityBoost;
    if (shieldActive) {
        stabilityBoost = 4.0f + proximity * 6.0f + speed * 3.0f + confidence * 2.0f;
        if (stabilityBoost < 4.0f) stabilityBoost = 4.0f;
        if (stabilityBoost > 15.0f) stabilityBoost = 15.0f;
    } else if (opponentDistance >= 1.0f && opponentDistance <= 500.0f) {
        float softP = 1.0f - opponentDistance / 500.0f;
        if (softP < 0.0f) softP = 0.0f;
        if (softP > 1.0f) softP = 1.0f;
        stabilityBoost = 3.0f + softP * 4.0f * confidence;
        if (stabilityBoost < 3.0f) stabilityBoost = 3.0f;
        if (stabilityBoost > 15.0f) stabilityBoost = 15.0f;
    } else if (confidence > 0.0f) {
        stabilityBoost = 3.0f;
    } else {
        stabilityBoost = 1.5f;
    }

    float controlRetentionBoost;
    if (confidence > 0.05f) {
        controlRetentionBoost = confidence * 0.6f + proximity * 0.4f;
    } else {
        controlRetentionBoost = proximity * 0.5f;
    }
    if (controlRetentionBoost < 0.0f) controlRetentionBoost = 0.0f;
    if (controlRetentionBoost > 1.0f) controlRetentionBoost = 1.0f;

    float turnAssist;
    if (turnIntensity > 0.05f) {
        float confClamped = confidence > 0.3f ? confidence : 0.3f;
        turnAssist = (turnIntensity * 0.7f + proximity * 0.3f) * confClamped;
    } else {
        turnAssist = 0.0f;
    }
    if (turnAssist < 0.0f) turnAssist = 0.0f;
    if (turnAssist > 1.0f) turnAssist = 1.0f;

    float shieldAngle;
    if (!isnan(playerX) && !isnan(playerY) && !isnan(oppX) && !isnan(oppY)) {
        float dx = oppX - playerX;
        float dy = oppY - playerY;
        float angleRad = atan2f(dy, dx);
        float angleDeg = angleRad * 57.29577951308232f;
        float trajectoryJitter = next_float() * 1.2f - 0.6f;
        float shieldDir = angleDeg + 180.0f + trajectoryJitter;
        shieldAngle = fmodf(fmodf(shieldDir, 360.0f) + 360.0f, 360.0f);
    } else {
        float bounded = fmodf(movementAngleDegrees, 360.0f);
        float angle;
        if (bounded < -180.0f) angle = bounded + 360.0f;
        else if (bounded > 180.0f) angle = bounded - 360.0f;
        else angle = bounded;
        
        float microDrift = next_float() * 1.3f - 0.65f;
        float res = (angle >= 0.0f) ? (angle + 90.0f + microDrift) : (angle - 90.0f + microDrift);
        shieldAngle = fmodf(fmodf(res, 360.0f) + 360.0f, 360.0f);
    }

    float shieldDuration;
    if (opponentDistance <= 0.0f) {
        shieldDuration = 45.0f + (float)next_int(-2, 3);
        if (shieldDuration < 40.0f) shieldDuration = 40.0f;
    } else {
        float normDist = opponentDistance / 100.0f;
        if (normDist < 0.5f) normDist = 0.5f;
        float proximityBonus = (float)(int32_t)(100.0f / normDist);
        if (proximityBonus < 0.0f) proximityBonus = 0.0f;
        if (proximityBonus > 60.0f) proximityBonus = 60.0f;
        
        float vel = playerVelocity > 0.0f ? playerVelocity : 0.0f;
        float velocityBonus = vel * 10.0f;
        if (velocityBonus < 0.0f) velocityBonus = 0.0f;
        if (velocityBonus > 15.0f) velocityBonus = 15.0f;
        
        float adaptiveJitter = (float)next_int(-3, 4);
        shieldDuration = 45.0f + proximityBonus + velocityBonus + adaptiveJitter;
        if (shieldDuration < 40.0f) shieldDuration = 40.0f;
        if (shieldDuration > 124.0f) shieldDuration = 124.0f;
    }

    result[0] = stabilityBoost;
    result[1] = controlRetentionBoost;
    result[2] = turnAssist;
    result[3] = shieldAngle;
    result[4] = shieldDuration;
    result[5] = shieldActive ? 1.0f : 0.0f;

    (*env)->SetFloatArrayRegion(env, outBuffer, 0, 6, result);
}

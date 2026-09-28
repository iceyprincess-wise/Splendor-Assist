#include <jni.h>
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

    if (distanceToAnchor > (screenWidth * 0.22f) && distanceToOpponent > intermediateThreatZone) {
        float dirAnchorX = dxAnchor * invAnchorDist;
        float dirAnchorY = dyAnchor * invAnchorDist;
        targetStickX = 250.0f + (dirAnchorX * 100.0f);
        targetStickY = 550.0f + (dirAnchorY * 100.0f);
        trackingPacingDuration = 33.33f;
    } 
    else if (isHoldingPressure && distanceToOpponent > 0.1f) {
        float oppLeadScale = 0.050f;
        float predOppX = oppX + (oppVx * oppLeadScale);
        float predOppY = oppY + (oppVy * oppLeadScale);
        float pdx = predOppX - playerX;
        float pdy = predOppY - playerY;
        float pDistSq = pdx * pdx + pdy * pdy;
        float invPDist = fast_inv_sqrt(pDistSq);
        float dirOppX = (pDistSq > 0.0f) ? (pdx * invPDist) : 0.0f;
        float dirOppY = (pDistSq > 0.0f) ? (pdy * invPDist) : 0.0f;

        targetStickX = 250.0f + (dirOppX * 100.0f);
        targetStickY = 550.0f + (dirOppY * 100.0f);

        if (distanceToOpponent <= closeCombatThreshold) {
            forcePhysicalBarge = 1.0f;
            trackingPacingDuration = 12.0f;
            targetStickX += (dirOppX * 30.0f);
            targetStickY += (dirOppY * 30.0f);
        } else if (distanceToOpponent <= intermediateThreatZone) {
            float speedFactor = oppVx * oppVx + oppVy * oppVy;
            if (speedFactor < 2.0f) {
                trackingPacingDuration = 20.0f;
                targetStickX += (dirOppX * 15.0f);
                targetStickY += (dirOppY * 15.0f);
            }
        }
    }

    result[0] = branchless_coerce(targetStickX, 0.0f, screenWidth);
    result[1] = branchless_coerce(targetStickY, 0.0f, screenHeight);
    result[2] = forcePhysicalBarge;
    result[3] = trackingPacingDuration;
    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}

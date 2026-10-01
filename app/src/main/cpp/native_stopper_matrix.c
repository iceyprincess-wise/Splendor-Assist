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

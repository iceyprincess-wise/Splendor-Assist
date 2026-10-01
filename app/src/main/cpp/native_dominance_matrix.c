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

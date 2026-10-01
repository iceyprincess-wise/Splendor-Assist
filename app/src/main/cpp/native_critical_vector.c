#include <jni.h>
#include <math.h>
#include <stdint.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeCriticalScoringVector(
        JNIEnv* env, jobject thiz,
        jfloat strikerX, jfloat strikerY,
        jfloat gkX, jfloat gkY,
        jfloat goalLeftPostX, jfloat goalLeftPostY,
        jfloat goalRightPostX, jfloat goalRightPostY,
        jfloat controlOriginX, jfloat controlOriginY, jfloat controlRadius,
        jfloatArray outBuffer) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!r) return;

    float gkDistToLeft = hypotf(goalLeftPostX - gkX, goalLeftPostY - gkY);
    float gkDistToRight = hypotf(goalRightPostX - gkX, goalRightPostY - gkY);

    // BRANCHLESS TARGET SELECTION
    float leftBias = branchless_coerce((gkDistToRight - gkDistToLeft) * 0.01f, 0.0f, 1.0f);
    float rightBias = 1.0f - leftBias;

    float targetPostX = (goalLeftPostX + 35.0f) * leftBias + (goalRightPostX - 35.0f) * rightBias;
    float targetPostY = (goalLeftPostY + 15.0f) * leftBias + (goalRightPostY + 15.0f) * rightBias;

    float firingAngle = atan2f(targetPostY - strikerY, targetPostX - strikerX);

    r[0] = controlOriginX + (cosf(firingAngle) * controlRadius);
    r[1] = controlOriginY + (sinf(firingAngle) * controlRadius);

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeCriticalTrueTargetPass(
        JNIEnv* env, jobject thiz,
        jfloat passButtonX, jfloat passButtonY,
        jfloat activeStrikerX, jfloat activeStrikerY,
        jfloat strikerVx, jfloat strikerVy,
        jboolean isLoftedContext,
        jfloat screenWidth, jfloat screenHeight,
        jfloatArray outBuffer) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!r) return;

    float velocityMagnitude = hypotf(strikerVx, strikerVy);
    
    // BRANCHLESS LEAD SCALING: Continuous interpolation instead of hard > 1.0f gate
    float velFactor = branchless_coerce(velocityMagnitude * 0.5f, 0.0f, 1.0f);
    float normalizedLead = 180.0f - (velFactor * 162.0f); // Smoothly transitions from 180.0 down to 18.0

    float destX = activeStrikerX + (strikerVx * normalizedLead);
    float destY = activeStrikerY + (strikerVy * normalizedLead);

    // BRANCHLESS CLAMPING
    float safeW = screenWidth > 200.0f ? screenWidth - 100.0f : 100.0f;
    float safeH = screenHeight > 200.0f ? screenHeight - 100.0f : 100.0f;
    
    r[0] = branchless_coerce(destX, 100.0f, safeW);
    r[1] = branchless_coerce(destY, 100.0f, safeH);

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

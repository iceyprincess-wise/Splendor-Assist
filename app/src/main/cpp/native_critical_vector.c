#include <jni.h>
#include <math.h>
#include <stdint.h>

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

    float targetPostX = (gkDistToLeft > gkDistToRight) ? goalLeftPostX + 35.0f : goalRightPostX - 35.0f;
    float targetPostY = (gkDistToLeft > gkDistToRight) ? goalLeftPostY + 15.0f : goalRightPostY + 15.0f;

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
    float normalizedLead = (velocityMagnitude > 1.0f) ? 18.0f : 180.0f;

    float destX = activeStrikerX + (strikerVx * normalizedLead);
    float destY = activeStrikerY + (strikerVy * normalizedLead);

    if (destX < 100.0f) destX = 100.0f;
    if (destX > screenWidth - 100.0f) destX = screenWidth - 100.0f;
    if (destY < 100.0f) destY = 100.0f;
    if (destY > screenHeight - 100.0f) destY = screenHeight - 100.0f;

    r[0] = destX;
    r[1] = destY;

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

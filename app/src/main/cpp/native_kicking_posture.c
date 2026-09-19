#include <jni.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeKickingPosture(
        JNIEnv* env, jobject thiz,
        jfloat carrierX, jfloat carrierY,
        jfloat carrierVx, jfloat carrierVy,
        jfloat targetX, jfloat targetY,
        jfloatArray outBuffer) {

    jfloat result[4];
    float dx = targetX - carrierX;
    float dy = targetY - carrierY;
    float targetAngleRad = atan2f(dy, dx);
    float vMagSq = carrierVx * carrierVx + carrierVy * carrierVy;
    float facingAngleRad = (vMagSq > 0.01f) ? atan2f(carrierVy, carrierVx) : targetAngleRad;

    float diffRad = targetAngleRad - facingAngleRad;
    while (diffRad > (float)M_PI) diffRad -= (2.0f * (float)M_PI);
    while (diffRad < -(float)M_PI) diffRad += (2.0f * (float)M_PI);

    float absDiffDeg = fabsf(diffRad) * 57.29577951308232f;
    float balanceScore = 1.0f - ((absDiffDeg - 45.0f) > 0.0f ? (absDiffDeg - 45.0f) : 0.0f) / 135.0f;
    if (balanceScore < 0.0f) balanceScore = 0.0f;
    if (balanceScore > 1.0f) balanceScore = 1.0f;

    float correctedX = targetX;
    float correctedY = targetY;
    jboolean requiresAdjustTouch = JNI_FALSE;

    if (absDiffDeg > 60.0f) {
        float maxCorrectionRad = 25.0f * 0.017453292519943295f;
        float sign = (diffRad > 0.0f) ? 1.0f : -1.0f;
        float correctedAngleRad = targetAngleRad - (sign * maxCorrectionRad);
        float dist = hypotf(dx, dy);
        correctedX = carrierX + cosf(correctedAngleRad) * dist;
        correctedY = carrierY + sinf(correctedAngleRad) * dist;
        if (correctedX < 0.0f) correctedX = 0.0f;
        if (correctedX > 1650.0f) correctedX = 1650.0f;
        if (correctedY < 0.0f) correctedY = 0.0f;
        if (correctedY > 720.0f) correctedY = 720.0f;
        if (absDiffDeg > 110.0f) requiresAdjustTouch = JNI_TRUE;
    }

    result[0] = correctedX;
    result[1] = correctedY;
    result[2] = balanceScore;
    result[3] = requiresAdjustTouch ? 1.0f : 0.0f;
    (*env)->SetFloatArrayRegion(env, outBuffer, 0, 4, result);
}

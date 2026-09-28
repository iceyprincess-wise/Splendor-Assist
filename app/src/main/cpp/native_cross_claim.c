#include <jni.h>
#include <stdint.h>

// CrossAction ordinals: 0=HOLD, 1=CLAIM, 2=PUNCH
// ShotDirection ordinals: 0=NEAR_POST, 1=FAR_POST, 2=CENTER, 3=CROSS, 4=LONG_BALL

JNIEXPORT jint JNICALL
Java_com_assistant_NativeBridge_nativeCrossClaimEvaluate(
        JNIEnv* env, jobject thiz,
        jint directionOrdinal, jint priority) {
    if (directionOrdinal != 3) return 0; // HOLD
    if (priority >= 140) return 2; // PUNCH
    if (priority >= 100) return 1; // CLAIM
    return 0; // HOLD
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeCrossClaimVector(
        JNIEnv* env, jobject thiz,
        jfloat width, jfloat height, jfloatArray outBuffer) {
    jfloat* result = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!result) return;
    result[0] = width * 0.50f;
    result[1] = height * 0.82f;
    result[2] = width * 0.50f;
    result[3] = height * 0.35f;
    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}

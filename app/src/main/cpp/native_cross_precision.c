#include <jni.h>
#include <stdint.h>

static inline float coerce(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeCrossPrecisionCalculate(
        JNIEnv* env, jobject thiz,
        jfloat x, jfloat y, jint strength, jfloatArray outBuffer) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!r) return;

    float boost = coerce((float)strength, 0.0f, 100.0f) / 100.0f;
    r[0] = x;
    r[1] = y - (40.0f * boost);
    r[2] = coerce(0.60f + (boost * 0.40f), 0.0f, 1.0f);

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

JNIEXPORT jfloat JNICALL
Java_com_assistant_NativeBridge_nativeCrossStunningLeadDistance(JNIEnv* env, jobject thiz, jfloat strikerVelocity) {
    return strikerVelocity * 0.5f;
}

JNIEXPORT jfloat JNICALL
Java_com_assistant_NativeBridge_nativeCrossStunningSwipeDistance(JNIEnv* env, jobject thiz) {
    return 320.0f;
}

JNIEXPORT jlong JNICALL
Java_com_assistant_NativeBridge_nativeCrossStunningDuration(JNIEnv* env, jobject thiz) {
    return 45L;
}

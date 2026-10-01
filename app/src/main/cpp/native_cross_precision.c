#include <jni.h>
#include <stdint.h>
#include <math.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeCrossPrecisionCalculate(
        JNIEnv* env, jobject thiz,
        jfloat x, jfloat y, jint strength, jfloatArray outBuffer) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!r) return;

    float boost = branchless_coerce((float)strength, 0.0f, 100.0f) * 0.01f;
    r[0] = x;
    r[1] = y - (40.0f * boost);
    r[2] = branchless_coerce(0.60f + (boost * 0.40f), 0.0f, 1.0f);

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

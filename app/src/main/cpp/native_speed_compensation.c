#include <jni.h>
#include <math.h>
#include <stdint.h>

static inline float coerce(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeSpeedCompensate(
    JNIEnv* env, jobject thiz, jfloat distance, jfloat angle, jint strength,
    jfloat angleJitter, jfloat execNoise, jfloat protNoise, jfloat pressNoise, jfloat laneNoise,
    jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    
    float factor = coerce((float)strength, 0.0f, 100.0f) / 100.0f;
    float distNorm = coerce(distance, 0.0f, 1000.0f) / 1000.0f;
    float containment = (fabsf(angle) > 45.0f) ? 15.0f + angleJitter : -15.0f + angleJitter;
    float proximity = 1.0f - distNorm;
    
    r[0] = containment;
    r[1] = coerce((10.0f + (factor * 15.0f) + (proximity * 9.0f)) + execNoise, 10.0f, 35.0f);
    r[2] = coerce((factor * 15.0f + proximity * 7.0f) + protNoise, 0.0f, 20.0f);
    r[3] = coerce((factor * 15.0f + proximity * 7.0f) + pressNoise, 0.0f, 20.0f);
    r[4] = coerce((factor * 14.0f + proximity * 8.0f) + laneNoise, 0.0f, 20.0f);
    
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

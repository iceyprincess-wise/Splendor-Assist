#include <jni.h>
#include <stdint.h>

static inline float coerce(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeShotOpportunityAnalyze(
    JNIEnv* env, jobject thiz, jfloat distance, jfloat pressure, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    
    float conf = coerce(1.0f - (distance / 1000.0f), 0.0f, 1.0f);
    float openSide = coerce((1.0f - pressure) * 4.00f, 0.0f, 4.0f);
    float pressScore = coerce(pressure, 0.0f, 1.0f);
    
    r[0] = conf;
    r[1] = openSide;
    r[2] = pressScore;
    
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

#include <jni.h>
#include <stdint.h>
#include <math.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeShotOpportunityAnalyze(
    JNIEnv* env, jobject thiz, jfloat distance, jfloat pressure, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    
    float conf = branchless_coerce(1.0f - (distance * 0.001f), 0.0f, 1.0f);
    float openSide = branchless_coerce((1.0f - pressure) * 4.00f, 0.0f, 4.0f);
    float pressScore = branchless_coerce(pressure, 0.0f, 1.0f);
    
    r[0] = conf;
    r[1] = openSide;
    r[2] = pressScore;
    
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

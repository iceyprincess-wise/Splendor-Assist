#include <jni.h>
#include <math.h>
#include <stdint.h>

// BRANCHLESS RANGE CLIPPING: Hardware-level selection abstraction
static inline float branchless_coerce(float v, float lo, float hi) {
    float r = v;
    r = 0.5f * (r + lo + fabsf(r - lo));
    r = 0.5f * (r + hi - fabsf(hi - r));
    return r;
}

// BRANCHLESS CONDITIONAL: Replaces if/else and ternary operators
static inline float branchless_select(float cond, float A, float B) {
    return (cond * A) + ((1.0f - cond) * B);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeSpeedCompensate(
    JNIEnv* env, jobject thiz, jfloat distance, jfloat angle, jint strength,
    jfloat angleJitter, jfloat execNoise, jfloat protNoise, jfloat pressNoise, jfloat laneNoise,
    jfloatArray out) {
    
    // ZERO-ALLOCATION JNI BOUNDARY: Direct register interactions
    jfloat* r = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    
    float factor = branchless_coerce((float)strength, 0.0f, 100.0f) * 0.01f;
    float distNorm = branchless_coerce(distance, 0.0f, 1000.0f) * 0.001f;
    
    // BRANCHLESS CONDITION: fabsf(angle) > 45.0f
    float absAngle = fabsf(angle);
    float diff = absAngle - 45.0f;
    // Fast branchless normalization to 0.0f or 1.0f
    float cond = fmaxf(0.0f, fminf(1.0f, diff * 10000.0f));
    
    float containmentA = 15.0f + angleJitter;
    float containmentB = -15.0f + angleJitter;
    float containment = branchless_select(cond, containmentA, containmentB);
    
    float proximity = 1.0f - distNorm;
    
    r[0] = containment;
    r[1] = branchless_coerce((10.0f + (factor * 15.0f) + (proximity * 9.0f)) + execNoise, 10.0f, 35.0f);
    r[2] = branchless_coerce((factor * 15.0f + proximity * 7.0f) + protNoise, 0.0f, 20.0f);
    r[3] = branchless_coerce((factor * 15.0f + proximity * 7.0f) + pressNoise, 0.0f, 20.0f);
    r[4] = branchless_coerce((factor * 14.0f + proximity * 8.0f) + laneNoise, 0.0f, 20.0f);
    
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

#include <jni.h>
#include <stdint.h>
#include <math.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeCounterattackAnalyze(
    JNIEnv* env, jobject thiz, jint attackers, jboolean teamShapeFound, jfloat teamShapeConf,
    jboolean offensiveLineFound, jfloat offensiveLineConf, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    
    float shapeMask = (float)teamShapeFound;
    float lineMask = (float)offensiveLineFound;
    
    float conf = (attackers * 0.090909f) + 
                 (shapeMask * 0.15f) + 
                 (lineMask * 0.15f) + 
                 (teamShapeConf * 0.05f) + 
                 (offensiveLineConf * 0.05f);
                 
    conf = branchless_coerce(conf, 0.0f, 1.0f);
    
    // NO HARD GATES. Unconditional aggressive output via smoothstep amplification
    float amplifiedConf = conf * conf * (3.0f - 2.0f * conf); 
    
    r[0] = amplifiedConf; 
    r[1] = conf;
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

#include <jni.h>
#include <stdint.h>

static inline float coerce(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeCounterattackAnalyze(
    JNIEnv* env, jobject thiz, jint attackers, jboolean teamShapeFound, jfloat teamShapeConf,
    jboolean offensiveLineFound, jfloat offensiveLineConf, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    float conf = (attackers / 11.0f) +
                 (teamShapeFound ? 0.15f : 0.0f) +
                 (offensiveLineFound ? 0.15f : 0.0f) +
                 (teamShapeConf * 0.05f) +
                 (offensiveLineConf * 0.05f);
    conf = coerce(conf, 0.0f, 1.0f);
    r[0] = (conf >= 0.60f) ? 1.0f : 0.0f;
    r[1] = conf;
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

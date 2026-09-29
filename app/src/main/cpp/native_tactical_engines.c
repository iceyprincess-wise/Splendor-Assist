#include <math.h>
#include <jni.h>
#include <stdint.h>

static inline float coerce(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeTacticalAnalytics(
    JNIEnv* env, jobject thiz,
    jfloat tacticalMapConf, jfloat compactnessConf, jfloat compactnessVal,
    jfloat wingConf, jboolean wingOverloaded,
    jfloat centralConf, jboolean centralOverloaded,
    jfloat pressingConf, jboolean pressingDetected,
    jfloat counterPressConf, jboolean counterPressDetected,
    jfloat buildUpConf, jboolean buildUpDetected,
    jfloat possessionConf, jboolean possessionDetected,
    jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    float score = tacticalMapConf + compactnessConf + compactnessVal + wingConf +
                  (wingOverloaded ? 0.05f : 0.0f) + centralConf +
                  (centralOverloaded ? 0.05f : 0.0f) + pressingConf +
                  (pressingDetected ? 0.05f : 0.0f) + counterPressConf +
                  (counterPressDetected ? 0.05f : 0.0f) + buildUpConf +
                  (buildUpDetected ? 0.05f : 0.0f) + possessionConf +
                  (possessionDetected ? 0.05f : 0.0f);
    r[0] = coerce(score / 8.4f, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeTacticalBehavior(
    JNIEnv* env, jobject thiz,
    jfloat analyticsConf, jfloat formationConf, jboolean formationFound,
    jfloat teamShapeConf, jfloat teamShapeCompactness,
    jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    float score = analyticsConf + formationConf +
                  (formationFound ? 0.20f : 0.0f) +
                  teamShapeConf + teamShapeCompactness;
    r[0] = coerce(score / 3.2f, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeRuntimeConfidenceCalibration(
    JNIEnv* env, jobject thiz,
    jfloat tacticalConf, jfloat formationConf,
    jfloat passingConf, jfloat shootingConf,
    jfloat ema, jfloat rollingMean, jfloat temporalConf,
    jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    float cal = (tacticalConf * 0.25f) + (formationConf * 0.20f) +
                (passingConf * 0.20f) + (shootingConf * 0.15f) +
                (ema * 0.10f) + (rollingMean * 0.05f) +
                (temporalConf * 0.05f);
    r[0] = coerce(cal, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeDefensiveCompactness(
    JNIEnv* env, jobject thiz,
    jfloat fieldConf, jfloat defLineConf, jfloat teamShapeConf,
    jfloat teamShapeWidth, jfloat teamShapeDepth, jfloat teamShapeCompactness,
    jfloat screenW, jfloat screenH, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    float maxSpread = sqrtf((screenW * screenW) + (screenH * screenH));
    if (maxSpread < 1.0f) maxSpread = 1.0f;
    
    r[0] = coerce(teamShapeWidth / screenW, 0.0f, 1.0f);
    r[1] = coerce(teamShapeDepth / screenH, 0.0f, 1.0f);
    r[2] = coerce(teamShapeCompactness / maxSpread, 0.0f, 1.0f);
    r[3] = coerce((fieldConf + defLineConf + teamShapeConf) / 3.0f, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

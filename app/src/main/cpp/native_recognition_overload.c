#include <jni.h>
#include <stdint.h>

static inline float coerce(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeWingOverloadCompute(
    JNIEnv* env, jobject thiz, jint playerCount, jfloat sceneConf, jfloat fieldConf,
    jint pressureRows, jint pressureCols, jint occupancyRows, jint occupancyCols, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    jboolean overloaded = (playerCount >= 8);
    float pressureOccFactor = (pressureRows > 0 && occupancyRows > 0) ? 1.0f : 0.0f;
    float conf = (sceneConf + fieldConf + pressureOccFactor) / 3.0f;
    r[0] = 0.5f; r[1] = 0.5f; r[2] = overloaded ? 1.0f : 0.0f; r[3] = coerce(conf, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeCentralOverloadCompute(
    JNIEnv* env, jobject thiz, jint playerCount, jfloat sceneConf, jfloat fieldConf, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    jboolean overloaded = (playerCount >= 8);
    r[0] = coerce(fieldConf, 0.0f, 1.0f); r[1] = overloaded ? 1.0f : 0.0f; r[2] = coerce(sceneConf, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativePressingRecognitionCompute(
    JNIEnv* env, jobject thiz, jint pressureRows, jint pressureCols,
    jboolean formationFound, jfloat formationConf, jfloat compactnessCompactness, jfloat compactnessConf, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    float pressureFactor = (pressureRows > 0 && pressureCols > 0) ? 1.0f : 0.0f;
    jboolean detected = formationFound && (compactnessCompactness > 0.55f);
    float conf = (formationConf + compactnessConf + pressureFactor) / 3.0f;
    r[0] = detected ? 1.0f : 0.0f; r[1] = coerce(conf, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeCounterPressCompute(
    JNIEnv* env, jobject thiz, jfloat sceneConf, jboolean hasPossession, jboolean possessionChanged, jfloat possessionConf,
    jint pressureRows, jint pressureCols, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    jboolean detected = hasPossession && possessionChanged;
    float pressureFactor = (pressureRows > 0 && pressureCols > 0) ? 1.0f : 0.0f;
    float conf = (sceneConf + possessionConf + pressureFactor) / 3.0f;
    r[0] = detected ? 1.0f : 0.0f; r[1] = coerce(conf, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeBuildUpRecognitionCompute(
    JNIEnv* env, jobject thiz, jboolean formationFound, jfloat formationConf, jfloat teamShapeConf,
    jboolean lanesNotEmpty, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    jboolean detected = formationFound && lanesNotEmpty;
    float laneFactor = lanesNotEmpty ? 1.0f : 0.0f;
    float conf = (formationConf + teamShapeConf + laneFactor) / 3.0f;
    r[0] = detected ? 1.0f : 0.0f; r[1] = coerce(conf, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativePossessionStyleCompute(
    JNIEnv* env, jobject thiz, jboolean hasPossession, jlong possessionFrames, jfloat possessionConf,
    jboolean lanesNotEmpty, jint pressureRows, jint pressureCols, jfloatArray out) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    jboolean detected = hasPossession && (possessionFrames > 30L);
    float pressureFactor = (pressureRows > 0 && pressureCols > 0) ? 1.0f : 0.0f;
    float laneFactor = lanesNotEmpty ? 1.0f : 0.0f;
    float conf = (possessionConf + laneFactor + pressureFactor) / 3.0f;
    r[0] = detected ? 1.0f : 0.0f; r[1] = coerce(conf, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

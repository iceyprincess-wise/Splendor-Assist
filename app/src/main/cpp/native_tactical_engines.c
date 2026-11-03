#include "native_breadcrumb.h"
#include "native_contract.h"
#include <math.h>
#include <jni.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#include <stdint.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
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
    if (!splendor_array(env, out, 1)) return;
    const jfloat contract_values[] = {tacticalMapConf, compactnessConf, compactnessVal, wingConf, centralConf, pressingConf, counterPressConf, buildUpConf, possessionConf};
    if (!splendor_finite(env, contract_values, sizeof(contract_values)/sizeof(contract_values[0]))) return;

#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeTacticalAnalytics");
#endif

    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    
    // BRANCHLESS BOOLEAN MULTIPLICATION
    float score = tacticalMapConf + compactnessConf + compactnessVal + wingConf +
                  ((float)wingOverloaded * 0.05f) + centralConf +
                  ((float)centralOverloaded * 0.05f) + pressingConf +
                  ((float)pressingDetected * 0.05f) + counterPressConf +
                  ((float)counterPressDetected * 0.05f) + buildUpConf +
                  ((float)buildUpDetected * 0.05f) + possessionConf +
                  ((float)possessionDetected * 0.05f);
    r[0] = branchless_coerce(score / 8.4f, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeTacticalBehavior(
    JNIEnv* env, jobject thiz,
    jfloat analyticsConf, jfloat formationConf, jboolean formationFound,
    jfloat teamShapeConf, jfloat teamShapeCompactness,
    jfloatArray out) {
    if (!splendor_array(env, out, 1)) return;
    const jfloat contract_values[] = {analyticsConf, formationConf, teamShapeConf, teamShapeCompactness};
    if (!splendor_finite(env, contract_values, sizeof(contract_values)/sizeof(contract_values[0]))) return;

#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeTacticalBehavior");
#endif

    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    float score = analyticsConf + formationConf +
                  ((float)formationFound * 0.20f) +
                  teamShapeConf + teamShapeCompactness;
    r[0] = branchless_coerce(score / 3.2f, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeRuntimeConfidenceCalibration(
    JNIEnv* env, jobject thiz,
    jfloat tacticalConf, jfloat formationConf,
    jfloat passingConf, jfloat shootingConf,
    jfloat ema, jfloat rollingMean, jfloat temporalConf,
    jfloatArray out) {
    if (!splendor_array(env, out, 1)) return;
    const jfloat contract_values[] = {tacticalConf, formationConf, passingConf, shootingConf, ema, rollingMean, temporalConf};
    if (!splendor_finite(env, contract_values, sizeof(contract_values)/sizeof(contract_values[0]))) return;

#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeRuntimeConfidenceCalibration");
#endif

    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    float cal = (tacticalConf * 0.25f) + (formationConf * 0.20f) +
                (passingConf * 0.20f) + (shootingConf * 0.15f) +
                (ema * 0.10f) + (rollingMean * 0.05f) +
                (temporalConf * 0.05f);
    r[0] = branchless_coerce(cal, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeDefensiveCompactness(
    JNIEnv* env, jobject thiz,
    jfloat fieldConf, jfloat defLineConf, jfloat teamShapeConf,
    jfloat teamShapeWidth, jfloat teamShapeDepth, jfloat teamShapeCompactness,
    jfloat screenW, jfloat screenH, jfloatArray out) {
    if (!splendor_array(env, out, 4)) return;
    const jfloat contract_values[] = {fieldConf, defLineConf, teamShapeConf, teamShapeWidth, teamShapeDepth, teamShapeCompactness, screenW, screenH};
    if (!splendor_finite(env, contract_values, sizeof(contract_values)/sizeof(contract_values[0]))) return;

#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeDefensiveCompactness");
#endif

    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    if (screenW <= 0 || screenH <= 0) {
        (*env)->ReleasePrimitiveArrayCritical(env, out, r, JNI_ABORT);
        splendor_reject(env, "Compactness requires positive frame dimensions"); return;
    }
    float maxSpread = sqrtf((screenW * screenW) + (screenH * screenH));
    float safeSpread = (maxSpread > 1.0f) ? maxSpread : 1.0f;
    
    r[0] = branchless_coerce(teamShapeWidth / screenW, 0.0f, 1.0f);
    r[1] = branchless_coerce(teamShapeDepth / screenH, 0.0f, 1.0f);
    r[2] = branchless_coerce(teamShapeCompactness / safeSpread, 0.0f, 1.0f);
    r[3] = branchless_coerce((fieldConf + defLineConf + teamShapeConf) / 3.0f, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}

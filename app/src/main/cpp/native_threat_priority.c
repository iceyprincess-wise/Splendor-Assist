#include <jni.h>
#include <math.h>
#include <stdint.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeThreatPriorityComputeImpl(
    JNIEnv *env, jclass clazz, jint threatScore, jint zone, jint direction,
    jint awareness, jint prediction, jint interceptionBonus, jint recoveryBonus,
    jint x, jint y, jint width, jint height, jfloatArray resultBuffer
) {
    (void)clazz;
    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);
    if (!res) return;

    float dir1Mask = (direction == 1) ? 1.0f : 0.0f;
    float dir2Mask = (direction == 2) ? 1.0f : 0.0f;
    float dirBonus = (dir1Mask * 35.0f) + (dir2Mask * 30.0f);

    float zone1Mask = (zone == 1) ? 1.0f : 0.0f;
    float zone2Mask = (zone == 2) ? 1.0f : 0.0f;
    float zone3Mask = (zone == 3) ? 1.0f : 0.0f;
    float zoneBonus = (zone1Mask * 40.0f) + (zone2Mask * 25.0f) + (zone3Mask * 10.0f);

    jint priority = threatScore + (jint)dirBonus + (awareness >> 1) + (prediction >> 1) + interceptionBonus + recoveryBonus + (jint)zoneBonus;

    float invHeight = (height > 0) ? (1.0f / (float)height) : 0.0f;
    float invWidth = (width > 0) ? (1.0f / (float)width) : 0.0f;
    
    float ny = (float)y * invHeight;
    float nx = (float)x * invWidth;
    
    float highMask = branchless_coerce((ny - 0.80f) * 10.0f, 0.0f, 1.0f);
    float lowMask = branchless_coerce((0.40f - ny) * 10.0f, 0.0f, 1.0f);
    float midMask = (1.0f - highMask) * (1.0f - lowMask);
    
    jint heightBand = (jint)(highMask * 2.0f + midMask * 1.0f + lowMask * 0.0f);

    res[0] = (float)priority;
    res[1] = (float)heightBand;
    res[2] = branchless_coerce(nx, 0.0f, 1.0f);

    (*env)->ReleasePrimitiveArrayCritical(env, resultBuffer, res, 0);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeThreatPriorityCompute(
    JNIEnv *env, jclass clazz, jint threatScore, jint zone, jint direction,
    jint awareness, jint prediction, jint interceptionBonus, jint recoveryBonus,
    jint x, jint y, jint width, jint height, jfloatArray resultBuffer
) {
    Java_com_assistant_NativeBridge_nativeThreatPriorityComputeImpl(env, clazz, threatScore, zone, direction, awareness, prediction, interceptionBonus, recoveryBonus, x, y, width, height, resultBuffer);
}

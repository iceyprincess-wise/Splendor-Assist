#include <jni.h>
#include <math.h>

static inline float splendor_coerce_in(float val, float min, float max) {
    float clamped_min = val < min ? min : val;
    return clamped_min > max ? max : clamped_min;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeThreatPriorityCompute(
    JNIEnv *env, jclass clazz, jint threatScore, jint zone, jint direction,
    jint awareness, jint prediction, jint interceptionBonus, jint recoveryBonus,
    jint x, jint y, jint width, jint height, jfloatArray resultBuffer
) {
    (void)clazz;
    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);
    if (!res) return;

    jint priority = threatScore;
    if (direction == 1) priority += 35;
    else if (direction == 2) priority += 30;

    priority += (awareness / 2) + (prediction / 2) + interceptionBonus + recoveryBonus;

    if (zone == 1) priority += 40;
    else if (zone == 2) priority += 25;
    else if (zone == 3) priority += 10;

    float ny = (height > 0) ? ((float)y / (float)height) : 0.5f;
    float nx = (width > 0) ? ((float)x / (float)width) : 0.5f;
    
    jint heightBand = 1;
    if (ny > 0.80f) heightBand = 2;
    else if (ny < 0.40f) heightBand = 0;

    res[0] = (float)priority;
    res[1] = (float)heightBand;
    res[2] = splendor_coerce_in(nx, 0.0f, 1.0f);

    (*env)->ReleasePrimitiveArrayCritical(env, resultBuffer, res, 0);
}

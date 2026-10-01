#include <jni.h>
#include <stdint.h>
#include <math.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeInjectStrobePackets(
    JNIEnv* env, jclass clazz,
    jfloat startX, jfloat startY, jfloat endX, jfloat endY,
    jint durationMs, jint pointerCount, jfloatArray outPackets) {
    
    jfloat* packets = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outPackets, NULL);
    if (!packets) return;

    int packetIdx = 0;
    int stride = durationMs / (pointerCount > 0 ? pointerCount : 1);
    if (stride < 1) stride = 1;
    
    float invDuration = (durationMs > 0) ? (1.0f / (float)durationMs) : 0.0f;
    float dx = endX - startX;
    float dy = endY - startY;

    for (int i = 0; i < pointerCount; i++) {
        int t = i * stride;
        float progress = (float)t * invDuration;
        progress = branchless_coerce(progress, 0.0f, 1.0f);
        
        float x = startX + dx * progress;
        float y = startY + dy * progress;

        packets[packetIdx++] = (float)i;
        packets[packetIdx++] = x;
        packets[packetIdx++] = y;
        packets[packetIdx++] = (float)t;
    }

    (*env)->ReleasePrimitiveArrayCritical(env, outPackets, packets, 0);
}

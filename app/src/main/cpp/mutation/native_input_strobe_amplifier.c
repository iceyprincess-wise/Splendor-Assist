#include <jni.h>
#include <stdint.h>

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeInjectStrobePacketsAmplifierRef(
    JNIEnv* env, jclass clazz,
    jfloat startX, jfloat startY, jfloat endX, jfloat endY,
    jint durationMs, jint pointerCount, jfloatArray outPackets) {
    
    jfloat* packets = (*env)->GetFloatArrayElements(env, outPackets, NULL);
    if (!packets) return;

    int packetIdx = 0;
    int stride = durationMs / pointerCount;
    if (stride < 1) stride = 1;

    for (int i = 0; i < pointerCount; i++) {
        int t = i * stride;
        float progress = (float)t / (float)durationMs;
        if (progress < 0.0f) progress = 0.0f;
        if (progress > 1.0f) progress = 1.0f;
        
        float x = startX + (endX - startX) * progress;
        float y = startY + (endY - startY) * progress;

        packets[packetIdx++] = (float)i;
        packets[packetIdx++] = x;
        packets[packetIdx++] = y;
        packets[packetIdx++] = (float)t;
    }

    (*env)->ReleaseFloatArrayElements(env, outPackets, packets, 0);
}

#include <jni.h>
#include <math.h>
#include <stdint.h>

static uint32_t g_rng_state = 123456789;

static inline uint32_t xorshift32() {
    g_rng_state ^= g_rng_state << 13;
    g_rng_state ^= g_rng_state >> 17;
    g_rng_state ^= g_rng_state << 5;
    return g_rng_state;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeSmoothVector(JNIEnv *env, jclass clazz,
                                                   jfloat startX, jfloat startY,
                                                   jfloat endX, jfloat endY,
                                                   jfloat durationMs,
                                                   jfloatArray outBuffer) {
    jfloat* result = (*env)->GetFloatArrayElements(env, outBuffer, NULL);
    if (!result) return;

    int steps = 10;
    float dt = 1.0f / (float)steps;
    float wobble_amp = 1.5f; 

    for (int i = 0; i <= steps; i++) {
        float t = i * dt;
        float cx = (startX + endX) * 0.5f + (float)(xorshift32() % 20 - 10);
        float cy = (startY + endY) * 0.5f + (float)(xorshift32() % 20 - 10);
        
        float u = 1.0f - t;
        float bx = u * u * startX + 2.0f * u * t * cx + t * t * endX;
        float by = u * u * startY + 2.0f * u * t * cy + t * t * endY;
        
        float wx = (float)(xorshift32() % 200 - 100) * 0.01f * wobble_amp;
        float wy = (float)(xorshift32() % 200 - 100) * 0.01f * wobble_amp;
        
        int idx = i * 3;
        result[idx] = bx + wx;
        result[idx + 1] = by + wy;
        result[idx + 2] = t * durationMs;
    }
    
    (*env)->ReleaseFloatArrayElements(env, outBuffer, result, 0);
}

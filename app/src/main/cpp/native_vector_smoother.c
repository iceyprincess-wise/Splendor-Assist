#include <jni.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#ifdef SPLENDOR_BREADCRUMBS
static void splendor_native_breadcrumb(const char *entry) {
    const char *path = "/data/user/0/com.assistant.overlay/files/native_breadcrumbs.txt";
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd >= 0) {
        (void)write(fd, entry, strlen(entry));
        (void)write(fd, "\n", 1);
        (void)close(fd);
    }
}
#endif
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
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeSmoothVector");
#endif

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

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
#include <stdint.h>
#include <math.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

static inline float eval_bezier_1d(float p0, float p1, float p2, float p3, float t) {
    float u = 1.0f - t;
    float tt = t * t;
    float uu = u * u;
    float uuu = uu * u;
    float ttt = tt * t;
    return uuu * p0 + 3.0f * uu * t * p1 + 3.0f * u * tt * p2 + ttt * p3;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeInjectStrobePackets(
    JNIEnv* env, jclass clazz,
    jfloat startX, jfloat startY, jfloat endX, jfloat endY,
    jint durationMs, jint pointerCount, jfloatArray outPackets) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeInjectStrobePackets");
#endif

    
    if (!outPackets) return;
    jfloat* packets = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outPackets, NULL);
    if (!packets) return;

    int count = pointerCount > 0 ? pointerCount : 1;
    int stride = durationMs / count;
    if (stride < 1) stride = 1;
    
    float invDuration = (durationMs > 0) ? (1.0f / (float)durationMs) : 0.0f;
    float dx = endX - startX;
    float dy = endY - startY;

    float ctrlX1 = startX + dx * 0.25f + dy * 0.05f;
    float ctrlY1 = startY + dy * 0.25f - dx * 0.05f;
    float ctrlX2 = startX + dx * 0.75f - dy * 0.05f;
    float ctrlY2 = startY + dy * 0.75f + dx * 0.05f;

    int packetIdx = 0;
    for (int i = 0; i < count; i++) {
        int t = i * stride;
        float progress = (float)t * invDuration;
        progress = branchless_coerce(progress, 0.0f, 1.0f);
        
        float x = eval_bezier_1d(startX, ctrlX1, ctrlX2, endX, progress);
        float y = eval_bezier_1d(startY, ctrlY1, ctrlY2, endY, progress);

        packets[packetIdx++] = (float)i;
        packets[packetIdx++] = x;
        packets[packetIdx++] = y;
        packets[packetIdx++] = (float)t;
    }

    (*env)->ReleasePrimitiveArrayCritical(env, outPackets, packets, 0);
}

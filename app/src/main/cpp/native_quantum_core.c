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
#include <sys/resource.h>
#include <unistd.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

typedef struct {
    float predPlayerX; float predPlayerY;
    float predOppX;    float predOppY;
    float lastRawOppX; float lastRawOppY;
    uint32_t internalTickCounter;
} QuantumTrackMatrix;

static QuantumTrackMatrix g_QuantumLoop = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0};

static inline float fast_inv_sqrt(float x) {
    float xhalf = 0.5f * x;
    union { int i; float f; } u;
    u.f = x;
    u.i = 0x5f3759df - (u.i >> 1);
    u.f = u.f * (1.5f - xhalf * u.f * u.f);
    return u.f;
}

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeBoostThreadPriority(JNIEnv* env, jobject thiz) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeBoostThreadPriority");
#endif

    setpriority(PRIO_PROCESS, 0, -10); 
}


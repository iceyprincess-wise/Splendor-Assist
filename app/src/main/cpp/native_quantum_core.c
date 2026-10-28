#include <jni.h>
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
    setpriority(PRIO_PROCESS, 0, -10); 
}


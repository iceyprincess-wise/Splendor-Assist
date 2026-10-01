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

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeExecuteQuantumSimulation(
        JNIEnv* env, jobject thiz,
        jfloat rawPlayerX, jfloat rawPlayerY,
        jfloat rawPlayerVx, jfloat rawPlayerVy,
        jfloat rawOppX, jfloat rawOppY,
        jfloat rawOppVx, jfloat rawOppVy,
        jfloat screenWidth, jfloat screenHeight,
        jfloatArray outBuffer) {

    jfloat* result = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!result) return;

    g_QuantumLoop.internalTickCounter++;
    float timeDeltaStep = 0.00833f;
    
    // BRANCHLESS STATE UPDATE: Continuous prediction without hard gates
    float diffMaskX = fabsf(rawOppX - g_QuantumLoop.lastRawOppX);
    float diffMaskY = fabsf(rawOppY - g_QuantumLoop.lastRawOppY);
    float updateMask = branchless_coerce((diffMaskX + diffMaskY - 0.01f) * 100.0f, 0.0f, 1.0f);
    
    g_QuantumLoop.predOppX = rawOppX * updateMask + (g_QuantumLoop.predOppX + rawOppVx * timeDeltaStep * 2.5f) * (1.0f - updateMask);
    g_QuantumLoop.predOppY = rawOppY * updateMask + (g_QuantumLoop.predOppY + rawOppVy * timeDeltaStep * 2.5f) * (1.0f - updateMask);
    g_QuantumLoop.predPlayerX = rawPlayerX * updateMask + (g_QuantumLoop.predPlayerX + rawPlayerVx * timeDeltaStep * 2.5f) * (1.0f - updateMask);
    g_QuantumLoop.predPlayerY = rawPlayerY * updateMask + (g_QuantumLoop.predPlayerY + rawPlayerVy * timeDeltaStep * 2.5f) * (1.0f - updateMask);
    
    g_QuantumLoop.lastRawOppX = rawOppX;
    g_QuantumLoop.lastRawOppY = rawOppY;

    float dx = g_QuantumLoop.predOppX - g_QuantumLoop.predPlayerX;
    float dy = g_QuantumLoop.predOppY - g_QuantumLoop.predPlayerY;
    float distSq = dx * dx + dy * dy;
    float invDist = fast_inv_sqrt(distSq);
    float distance = (distSq > 0.0f) ? (1.0f / invDist) : 0.0f;

    float dirX = dx * invDist;
    float dirY = dy * invDist;

    float targetStickX = 250.0f;
    float targetStickY = 550.0f;
    float strobeActionFlag = 0.0f;
    float touchHoldWindow = 33.33f; 

    // 🚨 OVERDRIVE INJECTION: Branchless Micro-Strobe Alternation
    float strobeMask = (float)(g_QuantumLoop.internalTickCounter & 1);
    float pulseMag = 85.0f + (strobeMask * 45.0f); // Alternates 85.0f / 130.0f

    float activeX = 250.0f + (dirX * pulseMag);
    float activeY = 550.0f + (dirY * pulseMag);
    
    // Continuous scaling based on distance - NO HARD GATES
    float closeFactor = branchless_coerce(1.0f - (distance / (screenHeight * 0.08f)), 0.0f, 1.0f);
    
    strobeActionFlag = (1.0f - strobeMask) * closeFactor;
    touchHoldWindow = 33.33f - (25.0f * closeFactor); // Drops to 8.33f continuously
    
    targetStickX = activeX + (dirX * 40.0f * closeFactor);
    targetStickY = activeY + (dirY * 40.0f * closeFactor);

    result[0] = fmaxf(0.0f, fminf(targetStickX, screenWidth));
    result[1] = fmaxf(0.0f, fminf(targetStickY, screenHeight));
    result[2] = strobeActionFlag;
    result[3] = touchHoldWindow;

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}

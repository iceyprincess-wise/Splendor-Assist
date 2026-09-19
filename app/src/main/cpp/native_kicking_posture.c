#include <jni.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

// Fast inline 1/sqrt approximation (Fast Inverse Square Root trick optimized for float)
static inline float fast_inv_sqrt(float x) {
    float xhalf = 0.5f * x;
    union { int i; float f; } u;
    u.f = x;
    u.i = 0x5f3759df - (u.i >> 1);
    u.f = u.f * (1.5f - xhalf * u.f * u.f);
    return u.f;
}

// Fast inline atan2f approximation using a minimax polynomial for ultra-low latency
static inline float fast_atan2f(float y, float x) {
    if (x == 0.0f && y == 0.0f) return 0.0f;
    float abs_y = fabsf(y);
    float abs_x = fabsf(x);
    float min_val = (abs_x < abs_y) ? abs_x : abs_y;
    float max_val = (abs_x > abs_y) ? abs_x : abs_y;
    if (max_val == 0.0f) return 0.0f;
    
    float r = min_val / max_val;
    float r2 = r * r;
    // Remez exchange algorithm polynomial approximation for atan(r) on [0, 1]
    float angle = (((-0.04649647f * r2 + 0.15931422f) * r2 - 0.32762281f) * r2 + 0.9998660f) * r;
    
    if (abs_y > abs_x) angle = (M_PI / 2.0f) - angle;
    if (x < 0.0f) angle = M_PI - angle;
    if (y < 0.0f) angle = -angle;
    return angle;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeKickingPosture(
        JNIEnv* env, jobject thiz,
        jfloat carrierX, jfloat carrierY,
        jfloat carrierVx, jfloat carrierVy,
        jfloat targetX, jfloat targetY,
        jfloat pitchWidth, jfloat pitchHeight, // Pass dynamic resolution to prevent bounding errors
        jfloatArray outBuffer) {

    // 1. Direct Critical Array Access to completely bypass JVM JNI synchronization overhead
    jfloat* result = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!result) return;

    float dx = targetX - carrierX;
    float dy = targetY - carrierY;
    
    // Fast trigonometry calculation
    float targetAngleRad = fast_atan2f(dy, dx);
    float vMagSq = carrierVx * carrierVx + carrierVy * carrierVy;
    float facingAngleRad = (vMagSq > 0.01f) ? fast_atan2f(carrierVy, carrierVx) : targetAngleRad;

    float diffRad = targetAngleRad - facingAngleRad;
    
    // 2. Eliminate hazardous while loops using mathematical wrap-around mechanics (Deterministic Branching)
    float q = floorf((diffRad + M_PI) * (1.0f / (2.0f * M_PI)));
    diffRad = diffRad - q * (2.0f * M_PI);

    float absDiffDeg = fabsf(diffRad) * 57.29578f;
    
    // 3. Optimize balance calculations with branchless clipping operations
    float balanceScore = 1.0f - ((absDiffDeg - 45.0f) > 0.0f ? (absDiffDeg - 45.0f) : 0.0f) * 0.007407407f; // Optimized division via multiplication
    balanceScore = (balanceScore < 0.0f) ? 0.0f : ((balanceScore > 1.0f) ? 1.0f : balanceScore);

    float correctedX = targetX;
    float correctedY = targetY;
    float requiresAdjustTouch = 0.0f;

    // 4. Vector Angle Adjustments for Fast Passing Transitions
    if (absDiffDeg > 60.0f) {
        float maxCorrectionRad = 0.4363323f; // 25.0 degrees pre-computed in radians
        float sign = (diffRad > 0.0f) ? 1.0f : -1.0f;
        float correctedAngleRad = targetAngleRad - (sign * maxCorrectionRad);
        
        // Optimize hypotf using our ultra-fast hardware inverse square root algorithm
        float dMagSq = dx * dx + dy * dy;
        float dist = (dMagSq > 0.0f) ? (1.0f / fast_inv_sqrt(dMagSq)) : 0.0f;
        
        correctedX = carrierX + cosf(correctedAngleRad) * dist;
        correctedY = carrierY + sinf(correctedAngleRad) * dist;
        
        // Dynamic clipping bounds based on game overlay framework dimensions
        correctedX = (correctedX < 0.0f) ? 0.0f : ((correctedX > pitchWidth) ? pitchWidth : correctedX);
        correctedY = (correctedY < 0.0f) ? 0.0f : ((correctedY > pitchHeight) ? pitchHeight : correctedY);
        
        if (absDiffDeg > 110.0f) {
            requiresAdjustTouch = 1.0f;
        }
    }

    // Direct buffer write
    result[0] = correctedX;
    result[1] = correctedY;
    result[2] = balanceScore;
    result[3] = requiresAdjustTouch;

    // Release direct memory lock instantly
    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}

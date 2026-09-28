#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <android/input.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

typedef struct {
    uint32_t isCounterAttack;
    uint32_t threatLevel;
    float ballX;
    float ballY;
    float carrierVx;
    float carrierVy;
    uint32_t lastServerTickTimestamp;
    uint32_t globalRngState;
} ApexStateMatrix;

static ApexStateMatrix g_StateMatrix = {0, 0, 0.0f, 0.0f, 0.0f, 0.0f, 0, 2463534242U};

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

static inline uint32_t local_xorshift32(uint32_t* state) {
    uint32_t x = *state;
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    *state = x;
    return x;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeUpdateStateMatrix(
        JNIEnv* env, jobject thiz,
        jboolean isCounter, jint threat, jfloat bx, jfloat by, jfloat bvx, jfloat bvy, jint serverTickTime) {
    g_StateMatrix.isCounterAttack = isCounter ? 1 : 0;
    g_StateMatrix.threatLevel = (uint32_t)threat;
    g_StateMatrix.ballX = bx;
    g_StateMatrix.ballY = by;
    g_StateMatrix.carrierVx = bvx;
    g_StateMatrix.carrierVy = bvy;
    g_StateMatrix.lastServerTickTimestamp = (uint32_t)serverTickTime;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeAnalyzeFrameBuffer(
        JNIEnv* env, jobject thiz,
        jobject bufferObj, jint width, jint height, jint stride, jfloatArray outCoordinates) {
    uint8_t* rawPixels = (uint8_t*)(*env)->GetDirectBufferAddress(env, bufferObj);
    if (!rawPixels) return;
    jfloat* coordinates = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outCoordinates, NULL);
    if (!coordinates) return;

    int elementIndex = 0;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int idx = (y * stride) + (x * 4);
            uint8_t r = rawPixels[idx];
            uint8_t g = rawPixels[idx + 1];
            uint8_t b = rawPixels[idx + 2];
            if (r > 240 && g < 30 && b < 30) { 
                coordinates[elementIndex++] = (float)x;
                coordinates[elementIndex++] = (float)y;
                if (elementIndex >= 30) goto processing_complete;
            }
        }
    }
processing_complete:
    (*env)->ReleasePrimitiveArrayCritical(env, outCoordinates, coordinates, 0);
}

JNIEXPORT jlong JNICALL
Java_com_assistant_NativeBridge_nativeCompileMotionEvent(
        JNIEnv* env, jobject thiz,
        jfloat startX, jfloat startY, jfloat endX, jfloat endY, jlong durationMs,
        jfloat pitchWidth, jfloat pitchHeight, jboolean detectedLagSpike) {
    uint32_t rng = local_xorshift32(&g_StateMatrix.globalRngState);
    float noiseOffset = ((float)(rng & 0xFF) / 255.0f) * 1.2f - 0.6f;
    float clampedEndX = branchless_coerce(endX + noiseOffset, 0.0f, pitchWidth);
    float clampedEndY = branchless_coerce(endY + noiseOffset, 0.0f, pitchHeight);

    uint32_t adjustedDuration = (uint32_t)durationMs;
    if (detectedLagSpike) {
        adjustedDuration = adjustedDuration + 24;
    }
    adjustedDuration = (adjustedDuration > 96) ? 96 : adjustedDuration;

    uint64_t packedData = 0;
    uint32_t packedX = (uint32_t)clampedEndX & 0xFFFF;
    uint32_t packedY = (uint32_t)clampedEndY & 0xFFFF;
    uint32_t packedDur = adjustedDuration & 0xFFFF;

    packedData |= ((uint64_t)packedX << 32);
    packedData |= ((uint64_t)packedY << 16);
    packedData |= packedDur;
    return (jlong)packedData;
}

#include "native_breadcrumb.h"
#include <jni.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#include <stdint.h>
#include <math.h>

JNIEXPORT jfloat JNICALL
Java_com_assistant_NativeBridge_nativeComputeOpticalVariance(
        JNIEnv* env, jobject thiz,
        jobject currentBuffer, jobject previousBuffer,
        jint width, jint height, jint stride,
        jint roiX, jint roiY, jint roiW, jint roiH) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeComputeOpticalVariance");
#endif

            
    if (!env || !currentBuffer || !previousBuffer || width <= 0 || height <= 0 || stride <= 0 ||
        (int64_t)stride < (int64_t)width * 4 || roiW <= 0 || roiH <= 0) return 0.0f;
    int64_t required = (int64_t)(height - 1) * stride + (int64_t)width * 4;
    if ((*env)->GetDirectBufferCapacity(env, currentBuffer) < required ||
        (*env)->GetDirectBufferCapacity(env, previousBuffer) < required) return 0.0f;
    uint8_t* curr = (uint8_t*)(*env)->GetDirectBufferAddress(env, currentBuffer);
    uint8_t* prev = (uint8_t*)(*env)->GetDirectBufferAddress(env, previousBuffer);
    if (!curr || !prev) return 0.0f;

    uint64_t sad = 0;
    uint64_t pixels = 0;

    int x0 = roiX < 0 ? 0 : roiX;
    int y0 = roiY < 0 ? 0 : roiY;
    int64_t right = (int64_t)roiX + roiW;
    int x1 = right > width ? width : (right < 0 ? 0 : (int)right);
    int64_t bottom = (int64_t)roiY + roiH;
    int y1 = bottom > height ? height : (bottom < 0 ? 0 : (int)bottom);

    for (int y = y0; y < y1; y += 4) {
        int64_t rowIdx = (int64_t)y * stride;
        for (int x = x0; x < x1; x += 4) {
            int64_t idx = rowIdx + ((int64_t)x * 4);
            int diffR = (int)curr[idx] - (int)prev[idx];
            int diffG = (int)curr[idx + 1] - (int)prev[idx + 1];
            int diffB = (int)curr[idx + 2] - (int)prev[idx + 2];
            
            sad += (diffR < 0 ? -diffR : diffR) +
                   (diffG < 0 ? -diffG : diffG) +
                   (diffB < 0 ? -diffB : diffB);
            pixels += 3;
        }
    }

    if (pixels == 0) return 0.0f;
    float variance = (float)sad / ((float)pixels * 255.0f);
    return variance > 1.0f ? 1.0f : variance;
}

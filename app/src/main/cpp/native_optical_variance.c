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

            
    if (!currentBuffer || !previousBuffer) return 0.0f;
    uint8_t* curr = (uint8_t*)(*env)->GetDirectBufferAddress(env, currentBuffer);
    uint8_t* prev = (uint8_t*)(*env)->GetDirectBufferAddress(env, previousBuffer);
    if (!curr || !prev) return 0.0f;

    uint64_t sad = 0;
    uint32_t pixels = 0;

    int x0 = roiX < 0 ? 0 : roiX;
    int y0 = roiY < 0 ? 0 : roiY;
    int x1 = (roiX + roiW) > width ? width : (roiX + roiW);
    int y1 = (roiY + roiH) > height ? height : (roiY + roiH);

    for (int y = y0; y < y1; y += 4) {
        int rowIdx = y * stride;
        for (int x = x0; x < x1; x += 4) {
            int idx = rowIdx + (x * 4);
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
    float variance = (float)sad / (float)(pixels * 255);
    return variance > 1.0f ? 1.0f : variance;
}

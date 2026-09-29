#include <jni.h>
#include <stdint.h>

JNIEXPORT jfloat JNICALL
Java_com_assistant_NativeBridge_nativeComputeOpticalVariance(
        JNIEnv* env, jobject thiz,
        jobject currentBuffer, jobject previousBuffer,
        jint width, jint height, jint stride,
        jint roiX, jint roiY, jint roiW, jint roiH) {
            
    uint8_t* curr = (uint8_t*)(*env)->GetDirectBufferAddress(env, currentBuffer);
    uint8_t* prev = (uint8_t*)(*env)->GetDirectBufferAddress(env, previousBuffer);
    if (!curr || !prev) return 0.0f;

    uint64_t sad = 0; // Sum of Absolute Differences
    uint32_t pixels = 0;

    // Clamp ROI to frame bounds
    int x0 = roiX < 0 ? 0 : roiX;
    int y0 = roiY < 0 ? 0 : roiY;
    int x1 = (roiX + roiW) > width ? width : (roiX + roiW);
    int y1 = (roiY + roiH) > height ? height : (roiY + roiH);

    for (int y = y0; y < y1; y++) {
        for (int x = x0; x < x1; x++) {
            int idx = (y * stride) + (x * 4); // Assuming RGBA_8888
            // Compare Red channel only for speed
            int diff = (int)curr[idx] - (int)prev[idx];
            sad += (diff < 0) ? -diff : diff;
            pixels++;
        }
    }

    if (pixels == 0) return 0.0f;
    // Normalize to 0.0 - 1.0 range (max diff per pixel is 255)
    float variance = (float)sad / (float)(pixels * 255);
    return variance > 1.0f ? 1.0f : variance;
}

#include <jni.h>
#include <stdint.h>

JNIEXPORT jint JNICALL
Java_com_assistant_NativeBridge_nativeComputeOpticalVariance(
    JNIEnv* env, jclass clazz,
    jobject prevBuffer, jobject currBuffer,
    jint width, jint height, jint stride,
    jfloatArray outCoords) {
    
    uint8_t* prev = (*env)->GetDirectBufferAddress(env, prevBuffer);
    uint8_t* curr = (*env)->GetDirectBufferAddress(env, currBuffer);
    jfloat* coords = (*env)->GetFloatArrayElements(env, outCoords, NULL);
    
    if (!prev || !curr || !coords) return 0;

    int varianceCount = 0;
    int maxBlobs = 64;
    int skip = 4;

    for (int y = 0; y < height; y += skip) {
        for (int x = 0; x < width; x += skip) {
            int idx = (y * stride) + (x * 4);
            int deltaR = curr[idx] - prev[idx];
            int deltaG = curr[idx+1] - prev[idx+1];
            int deltaB = curr[idx+2] - prev[idx+2];
            
            int deltaSq = (deltaR * deltaR) + (deltaG * deltaG) + (deltaB * deltaB);
            int threshold = 1024;
            
            if (deltaSq >= threshold && varianceCount < maxBlobs) {
                coords[varianceCount * 2] = (float)x;
                coords[varianceCount * 2 + 1] = (float)y;
                varianceCount++;
            }
        }
    }

    (*env)->ReleaseFloatArrayElements(env, outCoords, coords, 0);
    return varianceCount;
}

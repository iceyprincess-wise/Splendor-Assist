#include <jni.h>
#include <stdint.h>
#include <stddef.h>

static inline int splendor_clamp_max_int(int v, int max) {
    int gt = v > max;
    int mask = -gt;
    return v - (mask & (v - max));
}

JNIEXPORT jint JNICALL
Java_com_assistant_NativeBridge_nativePreprocessFrame(
    JNIEnv *env,
    jclass clazz,
    jobject srcBuf,
    jobject dstBuf,
    jint width,
    jint height,
    jint rowStride,
    jint inputSize,
    jint cameraProfileId
) {
    (void)clazz;
    (void)cameraProfileId;

    if (env == NULL || srcBuf == NULL || dstBuf == NULL) {
        return -1;
    }

    if (width <= 0 || height <= 0 || rowStride <= 0 || inputSize <= 0) {
        return -3;
    }

    uint8_t *src = (uint8_t *)(*env)->GetDirectBufferAddress(env, srcBuf);
    float *dst = (float *)(*env)->GetDirectBufferAddress(env, dstBuf);

    if (src == NULL || dst == NULL) {
        return -2;
    }

    jlong srcCap = (*env)->GetDirectBufferCapacity(env, srcBuf);
    jlong dstCap = (*env)->GetDirectBufferCapacity(env, dstBuf);

    if (srcCap < 0 || dstCap < 0) {
        return -6;
    }

    size_t requiredSrc =
        (size_t)(height - 1) * (size_t)rowStride + (size_t)width * 4u;

    size_t requiredDst =
        (size_t)inputSize * (size_t)inputSize * 3u * sizeof(float);

    if ((size_t)srcCap < requiredSrc) {
        return -7;
    }

    if ((size_t)dstCap < requiredDst) {
        return -8;
    }

    const float inv255 = 1.0f / 255.0f;
    const int maxSy = height - 1;
    const int maxSx = width - 1;

    for (int y = 0; y < inputSize; ++y) {
        int sy = (int)(((long)y * (long)height) / (long)inputSize);
        sy = splendor_clamp_max_int(sy, maxSy);

        const uint8_t *row = src + (size_t)sy * (size_t)rowStride;

        for (int x = 0; x < inputSize; ++x) {
            int sx = (int)(((long)x * (long)width) / (long)inputSize);
            sx = splendor_clamp_max_int(sx, maxSx);

            const uint8_t *p = row + (size_t)sx * 4u;

            *dst++ = (float)p[0] * inv255;
            *dst++ = (float)p[1] * inv255;
            *dst++ = (float)p[2] * inv255;
        }
    }

    return 0;
}

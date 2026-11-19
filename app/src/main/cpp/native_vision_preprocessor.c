#include "native_breadcrumb.h"
#include <jni.h>

#include <stdint.h>
#include <stddef.h>
#include <limits.h>

static inline int splendor_clamp_max_int(int v, int max) {
    int gt = v > max;
    int mask = -gt;
    return v - (mask & (v - max));
}

/* Return codes (No68-H-D59 — previously undocumented; the Kotlin caller
 * only checks == 0, so every failure looked identical):
 *   0  success
 *  -1  null env / src / dst buffer
 *  -2  src or dst is not a direct buffer (no address)
 *  -3  bad geometry (non-positive dims/stride/size, stride < width*4)
 *      or inputSize^2 * 3 * sizeof(float) overflows size_t
 *  -4, -5  unassigned
 *  -6  negative buffer capacity reported by the JVM
 *  -7  src buffer too small for (height-1)*rowStride + width*4
 *  -8  dst buffer too small for inputSize^2 * 3 floats
 */
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
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativePreprocessFrame");
#endif

    (void)clazz;
    // No68-H-D57: cameraProfileId is intentionally unused today — it is
    // the seam for a future second camera profile (cf. No62-D13, which
    // kept CameraProfile.activeProfileId() as the same seam). No
    // per-profile behaviour exists to implement; do not invent one.
    (void)cameraProfileId;

    if (env == NULL || srcBuf == NULL || dstBuf == NULL) {
        return -1;
    }

    if (width <= 0 || height <= 0 || rowStride <= 0 || inputSize <= 0 || (int64_t)rowStride < (int64_t)width * 4 ||
        (uint64_t)inputSize > SIZE_MAX / (uint64_t)inputSize / (3u * sizeof(float))) {
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

    /* P6 (batch 3) — hoist the per-pixel source-coordinate divisions.
     * sx depends only on x, not on y, so the loop below used to recompute
     * (x * width) / inputSize once per (x, y) pair — inputSize^2 integer
     * divisions per frame (102,400 at INPUT_SIZE 320). The table holds
     * exactly the values the old formula computes, so the sampled pixels,
     * the float math, the write order, and the outputs are bit-identical;
     * only redundant divisions are removed (2*inputSize divisions per
     * frame now: one table fill + one per row for sy). The NEON/SIMD
     * vectorisation the report sketches is NOT done here: the gather
     * pattern cannot be vectorised without changing the sampling, and
     * bit-identity could not be verified in the sandbox without ARM
     * execution — the division hoist is the dominant, provably-identical
     * win, verified by an x86 old-vs-new diff over randomized geometries
     * (see the batch-3 handoff). When inputSize exceeds the stack table
     * the original per-pixel formula runs instead (same outputs).
     */
    int sxTab[1024];
    const int *sxPtr = NULL;
    if (inputSize <= 1024) {
        for (int x = 0; x < inputSize; ++x) {
            int sx = (int)(((long)x * (long)width) / (long)inputSize);
            sxTab[x] = splendor_clamp_max_int(sx, maxSx);
        }
        sxPtr = sxTab;
    }

    for (int y = 0; y < inputSize; ++y) {
        int sy = (int)(((long)y * (long)height) / (long)inputSize);
        sy = splendor_clamp_max_int(sy, maxSy);

        const uint8_t *row = src + (size_t)sy * (size_t)rowStride;

        for (int x = 0; x < inputSize; ++x) {
            int sx;
            if (sxPtr != NULL) {
                sx = sxPtr[x];
            } else {
                sx = (int)(((long)x * (long)width) / (long)inputSize);
                sx = splendor_clamp_max_int(sx, maxSx);
            }

            const uint8_t *p = row + (size_t)sx * 4u;

            *dst++ = (float)p[0] * inv255;
            *dst++ = (float)p[1] * inv255;
            *dst++ = (float)p[2] * inv255;
        }
    }

    return 0;
}

#include "native_breadcrumb.h"
#include <jni.h>

#include <stdint.h>
#include <stddef.h>

/* Phase 2 — native_shot_scan (detection-stack engine #3, kills G9).
 *
 * The full-resolution, zero-allocation pixel scan — the native counterpart
 * to AdaptiveGaugeClassifier (Kotlin, owns the model and the decision).
 * This file owns ONLY the fast loop. It does NOT replace
 * ThreatClassifierEngine.classify by itself; the scan-wiring (W) piece
 * later calls it, and the classifier maps the reported score to a
 * ThreatType in Kotlin.
 *
 * Why native: the Kotlin scan (OmnipotentGoalkeeperEngine.kt
 * scanFrameForOpponentAnimation, :269-304) walks the frame with
 * ByteBuffer.get() per byte — ~2.6M px at full res — while this loop
 * walks raw memory with zero JNI crossings per pixel. The JNI cost is
 * less than the work eliminated. All decision / fusion / geometry logic
 * stays in Kotlin.
 *
 * ROI: the caller passes a buffer already delimited to the ROI (its
 * capacity / w / h describe the ROI, NOT the full frame). NO region is
 * hardcoded here — no bottom-25% (OGE:269), no gauge strip. Coordinates
 * in out[] are ROI-relative; the caller adds the ROI origin.
 * Pixel format: RGBA bytes, R at offset 0, G at +1, B at +2, alpha
 * ignored; pixelStride is bytes per pixel (4 for RGBA_8888) — never
 * assumed, unlike the Kotlin scan's hardcoded x * 4 (OGE:278).
 * Scan order mirrors the Kotlin scan: y outer, x inner, step
 * scanStride on both axes (Kotlin: x += 8, y += 8 — OGE:304), first
 * matching pixel wins.
 *
 * gaugeModel — the model IS the threshold input. Flat float[], length
 * 7 * R (R rules, 1..MAX_SHOT_SCAN_RULES). Per rule:
 *   [rLo, rHi, gLo, gHi, bLo, bHi, score]
 * A pixel matches a rule when rLo <= r <= rHi AND gLo <= g <= gHi AND
 * bLo <= b <= bHi (channel bytes 0..255, compared as floats). The best
 * (highest-score) matching rule supplies the pixel's score; ties go to
 * the lowest rule index. There are NO thresholds in this file beyond
 * what the caller passes in gaugeModel — AdaptiveGaugeClassifier owns
 * the learned values. C never maps a score to a threat: that decision
 * lives in Kotlin.
 *
 * out[] layout (caller supplies a FloatArray of length >= OUT_LEN):
 *   out[0] = found      (1.0f if a matching pixel was found, else 0.0f)
 *   out[1] = x          (ROI-relative x of the first matching pixel, -1 if none)
 *   out[2] = y          (ROI-relative y of the first matching pixel, -1 if none)
 *   out[3] = score      (best-rule score of the matched pixel, 0.0f if none)
 *   out[4] = ruleIndex  (index of the matched rule, -1 if none)
 *   out[5] = scanned    (pixels sampled this call, as a float)
 *
 * Return value (jint; mirrors the nativePreprocessFrame /
 * nativeExtractBlobs distinct-code convention, No68-H-D59):
 *   >= 0  success: pixels sampled (== (int) out[5])
 *   -1    null buffer / gaugeModel / out
 *   -2    buffer is not a direct buffer (no address) or capacity < 0
 *   -3    bad geometry (non-positive w/h, pixelStride < 3,
 *         rowStride < w * pixelStride, scanStride < 1, or the required
 *         byte count overflows int64)
 *   -4    direct buffer capacity smaller than the bytes the geometry needs
 *   -5    bad model (length not a multiple of 7, zero rules, more than
 *         MAX_SHOT_SCAN_RULES rules) or out array shorter than OUT_LEN
 *
 * Zero-alloc: no malloc anywhere. The model is copied once with
 * GetFloatArrayRegion into a fixed stack buffer; results are written
 * back once with SetFloatArrayRegion from a stack array. No
 * PrimitiveArrayCritical section is ever held across the scan loop
 * (holding one would block the GC for the whole frame).
 */

#define SHOT_SCAN_FLOATS_PER_RULE 7
#define SHOT_SCAN_MAX_RULES 16
#define SHOT_SCAN_OUT_LEN 6

JNIEXPORT jint JNICALL
Java_com_assistant_NativeBridge_nativeShotScan(
    JNIEnv *env,
    jclass clazz,
    jobject buffer,
    jint w,
    jint h,
    jint rowStride,
    jint pixelStride,
    jfloatArray gaugeModel,
    jint scanStride,
    jfloatArray out
) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeShotScan");
#endif

    (void)clazz;

    if (env == NULL || buffer == NULL || gaugeModel == NULL || out == NULL) {
        return -1;
    }

    if (w <= 0 || h <= 0 || pixelStride < 3 || scanStride < 1 ||
        (int64_t)rowStride < (int64_t)w * (int64_t)pixelStride) {
        return -3;
    }

    const jsize modelLen = (*env)->GetArrayLength(env, gaugeModel);
    if (modelLen <= 0 || (modelLen % SHOT_SCAN_FLOATS_PER_RULE) != 0 ||
        modelLen / SHOT_SCAN_FLOATS_PER_RULE > SHOT_SCAN_MAX_RULES) {
        return -5;
    }
    if ((*env)->GetArrayLength(env, out) < SHOT_SCAN_OUT_LEN) {
        return -5;
    }

    uint8_t *pixels = (uint8_t *)(*env)->GetDirectBufferAddress(env, buffer);
    if (pixels == NULL) {
        return -2;
    }

    const jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (capacity < 0) {
        return -2;
    }

    const int64_t requiredBytes =
        (int64_t)(h - 1) * (int64_t)rowStride +
        (int64_t)(w - 1) * (int64_t)pixelStride + 3;
    if (requiredBytes < 0 || (int64_t)capacity < requiredBytes) {
        return -4;
    }

    /* One copy of the (small) model into stack storage, before the loop. */
    float model[SHOT_SCAN_MAX_RULES * SHOT_SCAN_FLOATS_PER_RULE];
    (*env)->GetFloatArrayRegion(env, gaugeModel, 0, modelLen, model);
    if ((*env)->ExceptionCheck(env)) {
        return -5;
    }
    const int ruleCount = modelLen / SHOT_SCAN_FLOATS_PER_RULE;

    float result[SHOT_SCAN_OUT_LEN];
    result[0] = 0.0f;
    result[1] = -1.0f;
    result[2] = -1.0f;
    result[3] = 0.0f;
    result[4] = -1.0f;
    result[5] = 0.0f;

    int scanned = 0;
    for (int y = 0; y < h; y += scanStride) {
        const uint8_t *row = pixels + (size_t)y * (size_t)rowStride;
        for (int x = 0; x < w; x += scanStride) {
            const uint8_t *p = row + (size_t)x * (size_t)pixelStride;
            const float r = (float)p[0];
            const float g = (float)p[1];
            const float b = (float)p[2];
            ++scanned;

            float bestScore = 0.0f;
            int bestRule = -1;
            for (int i = 0; i < ruleCount; ++i) {
                const float *rule = model + (size_t)i * SHOT_SCAN_FLOATS_PER_RULE;
                if (r >= rule[0] && r <= rule[1] &&
                    g >= rule[2] && g <= rule[3] &&
                    b >= rule[4] && b <= rule[5] &&
                    (bestRule < 0 || rule[6] > bestScore)) {
                    bestScore = rule[6];
                    bestRule = i;
                }
            }
            if (bestRule >= 0) {
                result[0] = 1.0f;
                result[1] = (float)x;
                result[2] = (float)y;
                result[3] = bestScore;
                result[4] = (float)bestRule;
                result[5] = (float)scanned;
                (*env)->SetFloatArrayRegion(env, out, 0, SHOT_SCAN_OUT_LEN, result);
                return scanned;
            }
        }
    }

    result[5] = (float)scanned;
    (*env)->SetFloatArrayRegion(env, out, 0, SHOT_SCAN_OUT_LEN, result);
    return scanned;
}

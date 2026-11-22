#include "native_breadcrumb.h"
#include "native_contract.h"
#include <jni.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#include <math.h>
#include <stdint.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeCriticalScoringVector(
        JNIEnv* env, jobject thiz,
        jfloat strikerX, jfloat strikerY,
        jfloat gkX, jfloat gkY,
        jfloat goalLeftPostX, jfloat goalLeftPostY,
        jfloat goalRightPostX, jfloat goalRightPostY,
        jfloat controlOriginX, jfloat controlOriginY, jfloat controlRadius,
        jfloatArray outBuffer) {
    if (!splendor_array(env, outBuffer, 2)) return;
    const jfloat contract_values[] = {strikerX, strikerY, gkX, gkY, goalLeftPostX, goalLeftPostY, goalRightPostX, goalRightPostY, controlOriginX, controlOriginY, controlRadius};
    if (!splendor_finite(env, contract_values, sizeof(contract_values)/sizeof(contract_values[0]))) return;

#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeCriticalScoringVector");
#endif

    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!r) return;

    float gkDistToLeft = hypotf(goalLeftPostX - gkX, goalLeftPostY - gkY);
    float gkDistToRight = hypotf(goalRightPostX - gkX, goalRightPostY - gkY);

    // BRANCHLESS TARGET SELECTION — Q-C3-1 (F01/F02/F03).
    // F01: bias flipped to the FAR post (was: gkDistToRight - gkDistToLeft,
    // which targeted the NEAR post in both cases). The old 0.01f blend gain
    // is superseded by the hard-pick step below, per the delegated design.
    // F02: hard pick of the farther corner — a saturated branchless step, so
    // the pick is 0 or 1 and the target is one inset corner or the other,
    // never the midpoint (a centred keeper + a blend aims at the keeper's
    // body). Tie -> left (deterministic): at tie the difference is 0, so
    // leftBias = coerce(1 + 0) = 1 -> left post. Keeper near the left post
    // (diff < 0) -> leftBias 0 -> right post (far); keeper near the right
    // post (diff > 0) -> leftBias 1 -> left post (far).
    // NOTE: the pick rule here MUST stay identical to ShotTargetMath.kt's
    // farCornerTarget (farther post, tie -> left, same 35/15 insets) — the
    // Kotlin side computes the radius to the corner this picks, so if the
    // two ever disagree the native lands off-target.
    float leftBias = branchless_coerce(1.0f + (gkDistToLeft - gkDistToRight) * 1000.0f, 0.0f, 1.0f);
    float rightBias = 1.0f - leftBias;

    float targetPostX = (goalLeftPostX + 35.0f) * leftBias + (goalRightPostX - 35.0f) * rightBias;
    // F03: right-post Y inset is -15, not +15. Y grows downward in capture
    // space and the right post is the BOTTOM post, so +15 landed 15px
    // OUTSIDE the mouth. Left post is the TOP post: +15 stays inward.
    float targetPostY = (goalLeftPostY + 15.0f) * leftBias + (goalRightPostY - 15.0f) * rightBias;

    float firingAngle = atan2f(targetPostY - strikerY, targetPostX - strikerX);

    r[0] = controlOriginX + (cosf(firingAngle) * controlRadius);
    r[1] = controlOriginY + (sinf(firingAngle) * controlRadius);

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeCriticalTrueTargetPass(
        JNIEnv* env, jobject thiz,
        jfloat passButtonX, jfloat passButtonY,
        jfloat activeStrikerX, jfloat activeStrikerY,
        jfloat strikerVx, jfloat strikerVy,
        jboolean isLoftedContext,
        jfloat screenWidth, jfloat screenHeight,
        jfloatArray outBuffer) {
    if (!splendor_array(env, outBuffer, 2)) return;
    // 6a: finite-input contract (Task-5 class), mirroring
    // nativeCriticalScoringVector above. passButtonX/Y and
    // isLoftedContext are accepted but intentionally unused by the math
    // (the target is the striker's velocity lead point, not the button);
    // they stay in the signature because the JNI + Kotlin declarations
    // carry them — removing them would break the ABI for no behaviour
    // gain. Only the FINITE inputs are validated here.
    const jfloat contract_values[] = {passButtonX, passButtonY, activeStrikerX, activeStrikerY, strikerVx, strikerVy, screenWidth, screenHeight};
    if (!splendor_finite(env, contract_values, sizeof(contract_values)/sizeof(contract_values[0]))) return;

#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeCriticalTrueTargetPass");
#endif

    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!r) return;

    float velocityMagnitude = hypotf(strikerVx, strikerVy);
    
    // BRANCHLESS LEAD SCALING: Continuous interpolation instead of hard > 1.0f gate
    float velFactor = branchless_coerce(velocityMagnitude * 0.5f, 0.0f, 1.0f);
    float normalizedLead = 180.0f - (velFactor * 162.0f); // Smoothly transitions from 180.0 down to 18.0

    float destX = activeStrikerX + (strikerVx * normalizedLead);
    float destY = activeStrikerY + (strikerVy * normalizedLead);

    // BRANCHLESS CLAMPING
    float safeW = screenWidth > 200.0f ? screenWidth - 100.0f : 100.0f;
    float safeH = screenHeight > 200.0f ? screenHeight - 100.0f : 100.0f;
    
    r[0] = branchless_coerce(destX, 100.0f, safeW);
    r[1] = branchless_coerce(destY, 100.0f, safeH);

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

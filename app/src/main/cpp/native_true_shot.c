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
Java_com_assistant_NativeBridge_nativeTrueShotCompute(
        JNIEnv* env, jobject thiz,
        jfloat ballX, jfloat ballY,
        jfloat goalLeftX, jfloat goalRightX, jfloat goalTopY, jfloat goalBottomY,
        jfloat goalkeeperX, jboolean goalkeeperVisible, jboolean goalDetected,
        jfloat defenderDensity, jfloat screenW, jfloat screenH,
        jfloatArray outBuffer) {
    if (!splendor_array(env, outBuffer, 5)) return;
    const jfloat contract_values[] = {ballX, ballY, goalLeftX, goalRightX, goalTopY, goalBottomY, goalkeeperX, defenderDensity, screenW, screenH};
    if (!splendor_finite(env, contract_values, sizeof(contract_values)/sizeof(contract_values[0]))) return;

#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeTrueShotCompute");
#endif

            
    jfloat* result = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!result) return;

    float MAX_SHOT_DIST = 700.0f;

    float goalDetMask = (float)goalDetected;
    float goalCX = goalLeftX * 0.5f + goalRightX * 0.5f;
    goalCX = goalCX * goalDetMask + screenW * (1.0f - goalDetMask);
    
    float goalCY = goalTopY * 0.5f + goalBottomY * 0.5f;
    goalCY = goalCY * goalDetMask + ballY * (1.0f - goalDetMask);

    float dx = ballX - goalCX;
    float dy = ballY - goalCY;
    float distSq = dx * dx + dy * dy;
    float dist = (distSq > 0.0f) ? sqrtf(distSq) : 0.0f;

    // NO HARD GATES. Continuous authority scaling. NEVER ABORTS.
    float distFactor = branchless_coerce(1.0f - (dist / MAX_SHOT_DIST), 0.0f, 1.0f);
    
    float mid = goalLeftX * 0.5f + goalRightX * 0.5f;
    float gkVisMask = (float)goalkeeperVisible;
    // No68-F-D17: the old names were swapped — openXLeft held the
    // RIGHT-side value (goalCX + right half) and openXRight the LEFT-side
    // value. Renamed to match the values; the arithmetic is unchanged.
    // No68-F-D18: the old keeper-offset weight saturated at a fixed 100 px
    // from centre ((mid - goalkeeperX) * 0.01) regardless of capture size
    // or goal width, so on a small/far goal (half-width < 100 px) the aim
    // never reached the far side even with the keeper on the post. The
    // weight is now the keeper's offset as a fraction of the goal
    // half-width: 1.0 when the keeper stands on the post, 0.0 at centre.
    // Identical to the old form when the goal half-width is 100 px.
    // A degenerate box (half-width <= 0) aims midway (0.5).
    float halfGoalW = (goalRightX - goalLeftX) * 0.5f;
    float awayFromKeeper = (halfGoalW > 0.0f)
        ? branchless_coerce((mid - goalkeeperX) / halfGoalW, 0.0f, 1.0f)
        : 0.5f;
    float openXRight = goalCX + (goalRightX - goalCX) * 0.72f;
    float openXLeft = goalCX - (goalCX - goalLeftX) * 0.72f;
    float gkOffsetX = openXRight * awayFromKeeper + openXLeft * (1.0f - awayFromKeeper);
    float openX = goalCX * (1.0f - gkVisMask) + gkOffsetX * gkVisMask;
    
    // Clamp to goal bounds branchlessly
    openX = branchless_coerce(openX, goalLeftX, goalRightX);

    float proximity = distFactor;
    float densityPenalty = branchless_coerce(defenderDensity, 0.0f, 1.0f);
    float authority = (proximity * 0.70f + (1.0f - densityPenalty * 0.35f) * 0.30f);
    authority = branchless_coerce(authority, 0.0f, 1.0f);

    // Clamp to screen bounds branchlessly
    openX = branchless_coerce(openX, 0.0f, screenW);
    goalCY = branchless_coerce(goalCY, 0.0f, screenH);

    // Shot core fix (item 3): the default aim height is LOW, not mid.
    // Mid-height (goalCY) aims at the keeper's chest and, on far-corner
    // paths, at the bar line. Low = today's POWER placement
    // (goalBottom - 0.15 * height); ShotTypeEngine still owns the CHIP
    // high aim on the Kotlin side. goalCY above is untouched — it stays
    // the distance anchor.
    float goalHeight = goalBottomY - goalTopY;
    if (goalHeight < 1.0f) goalHeight = 1.0f;
    float aimY = goalBottomY - goalHeight * 0.15f;
    aimY = branchless_coerce(aimY, goalTopY, goalBottomY);
    aimY = branchless_coerce(aimY, 0.0f, screenH);

    result[0] = 1.0f; // ALWAYS VALID - NO ABORTS
    result[1] = openX;
    result[2] = aimY;
    result[3] = authority;
    result[4] = goalDetMask; // onTarget

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}

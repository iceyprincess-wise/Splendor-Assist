#include <jni.h>
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
    float leftOpenMask = branchless_coerce((mid - goalkeeperX) * 0.01f, 0.0f, 1.0f);
    float rightOpenMask = 1.0f - leftOpenMask;
    
    float openXLeft = goalCX + (goalRightX - goalCX) * 0.72f;
    float openXRight = goalCX - (goalCX - goalLeftX) * 0.72f;
    
    float gkOffsetX = openXLeft * leftOpenMask + openXRight * rightOpenMask;
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

    result[0] = 1.0f; // ALWAYS VALID - NO ABORTS
    result[1] = openX;
    result[2] = goalCY;
    result[3] = authority;
    result[4] = goalDetMask; // onTarget

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}

#include <jni.h>
#include <math.h>
#include <stdint.h>

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
    float MIN_SHOT_DIST = 25.0f;

    float goalCX = goalDetected ? (goalLeftX + goalRightX) * 0.5f : screenW;
    float goalCY = goalDetected ? (goalTopY + goalBottomY) * 0.5f : ballY;

    float dx = ballX - goalCX;
    float dy = ballY - goalCY;
    float dist = sqrtf(dx * dx + dy * dy);

    if (dist > MAX_SHOT_DIST || dist < MIN_SHOT_DIST) {
        result[0] = 0.0f; // Invalid
        (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
        return;
    }

    float openX = goalCX;
    if (goalDetected && goalkeeperVisible && goalkeeperX > 0.0f) {
        float mid = (goalLeftX + goalRightX) * 0.5f;
        if (goalkeeperX <= mid) {
            openX = goalCX + (goalRightX - goalCX) * 0.72f;
        } else {
            openX = goalCX - (goalCX - goalLeftX) * 0.72f;
        }
        // Clamp to goal bounds
        if (openX < goalLeftX) openX = goalLeftX;
        if (openX > goalRightX) openX = goalRightX;
    }

    float proximity = 1.0f - (dist / MAX_SHOT_DIST);
    float authority = (proximity * 0.70f + (1.0f - defenderDensity * 0.35f) * 0.30f);
    if (authority < 0.0f) authority = 0.0f;
    if (authority > 1.0f) authority = 1.0f;

    // Clamp to screen bounds
    if (openX < 0.0f) openX = 0.0f;
    if (openX > screenW) openX = screenW;
    if (goalCY < 0.0f) goalCY = 0.0f;
    if (goalCY > screenH) goalCY = screenH;

    result[0] = 1.0f; // Valid
    result[1] = openX;
    result[2] = goalCY;
    result[3] = authority;
    result[4] = goalDetected ? 1.0f : 0.0f; // onTarget

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}

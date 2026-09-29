#include <jni.h>
#include <math.h>
#include <stdint.h>

static inline float coerce(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeSACorrectPass(
        JNIEnv* env, jobject thiz,
        jfloat ballX, jfloat ballY, jfloat intendedX, jfloat intendedY,
        jfloat receiverVx, jfloat receiverVy, jfloat nearestOppX, jfloat nearestOppY,
        jfloat pressure, jfloat screenW, jfloat screenH, jfloatArray outBuffer) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!r) return;
    float dx = intendedX - ballX;
    float dy = intendedY - ballY;
    float dist = sqrtf(dx*dx + dy*dy);
    float travelS = coerce(dist / 800.0f + 0.16f, 0.0f, 0.55f);
    float predX = coerce(intendedX + receiverVx * 60.0f * travelS, 0.0f, screenW);
    float predY = coerce(intendedY + receiverVy * 60.0f * travelS, 0.0f, screenH);
    float saDx = nearestOppX - intendedX;
    float saDy = nearestOppY - intendedY;
    r[0] = coerce(predX - saDx * 0.40f, 0.0f, screenW);
    r[1] = coerce(predY - saDy * 0.40f, 0.0f, screenH);
    r[2] = coerce(0.55f + pressure * 0.45f, 0.0f, 1.0f);
    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeSACorrectShot(
        JNIEnv* env, jobject thiz,
        jfloat ballX, jfloat ballY, jfloat glX, jfloat grX, jfloat gtY, jfloat gbY,
        jfloat gkX, jboolean gkVis, jboolean goalDet, jfloat screenW, jfloat screenH, jfloatArray outBuffer) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!r) return;
    float minX = glX <= grX ? glX : grX; float maxX = glX <= grX ? grX : glX;
    float minY = gtY <= gbY ? gtY : gbY; float maxY = gtY <= gbY ? gbY : gtY;
    float goalCX = goalDet ? (minX + maxX) * 0.5f : screenW;
    float goalCY = goalDet ? (minY + maxY) * 0.5f : ballY;
    float dx = ballX - goalCX; float dy = ballY - goalCY;
    float dist = sqrtf(dx*dx + dy*dy);
    if (dist > 720.0f) { r[3] = 0.0f; (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0); return; }
    float openX = goalCX;
    if (goalDet && gkVis && gkX > 0.0f) {
        openX = (gkX <= goalCX) ? (goalCX + (maxX - goalCX) * 0.70f) : (goalCX - (goalCX - minX) * 0.70f);
        openX = coerce(openX, minX, maxX);
    }
    float proximity = 1.0f - (dist / 720.0f);
    r[0] = coerce(openX, 0.0f, screenW);
    r[1] = coerce(goalCY, 0.0f, screenH);
    r[2] = coerce(0.70f + proximity * 0.30f, 0.0f, 1.0f);
    r[3] = 1.0f;
    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeSACorrectCross(
        JNIEnv* env, jobject thiz,
        jfloat ballX, jfloat ballY, jfloat rx, jfloat ry, jfloat rvx, jfloat rvy,
        jfloat gcx, jfloat gcy, jfloat laneScore, jfloat screenW, jfloat screenH, jfloatArray outBuffer) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!r) return;
    float dx = rx - ballX; float dy = ry - ballY;
    float dist = sqrtf(dx*dx + dy*dy);
    if (dist > 900.0f || laneScore < 0.04f) { r[3] = 0.0f; (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0); return; }
    float travelS = coerce(dist / 700.0f + 0.06f, 0.0f, 0.55f);
    float predX = coerce(rx + rvx * 60.0f * travelS, 0.0f, screenW);
    float predY = coerce(ry + rvy * 60.0f * travelS, 0.0f, screenH);
    float dpdx = predX - gcx; float dpdy = predY - gcy;
    float distPredToGoal = sqrtf(dpdx*dpdx + dpdy*dpdy);
    float targetX, targetY;
    if (distPredToGoal < 220.0f) { targetX = predX; targetY = predY; }
    else {
        float penX = coerce(gcx - 160.0f, 0.0f, screenW);
        targetX = coerce(predX * 0.55f + penX * 0.45f, 0.0f, screenW);
        targetY = coerce(predY * 0.55f + gcy * 0.45f, 0.0f, screenH);
    }
    r[0] = targetX; r[1] = targetY;
    r[2] = coerce(laneScore * 0.75f + 0.25f * (1.0f - dist / 900.0f), 0.0f, 1.0f);
    r[3] = 1.0f;
    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeSACorrectKeeper(
        JNIEnv* env, jobject thiz,
        jfloat ballX, jfloat ballY, jfloat glX, jfloat grX, jfloat gtY, jfloat gbY,
        jfloat screenW, jfloat screenH, jfloatArray outBuffer) {
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!r) return;
    float minY = gtY <= gbY ? gtY : gbY; float maxY = gtY <= gbY ? gbY : gtY;
    float goalMidY = (minY > 0.0f && maxY > minY) ? (minY + maxY) * 0.5f : ballY;
    float gl = glX <= grX ? glX : grX; float gr = glX <= grX ? grX : glX;
    float interceptX = coerce(ballX, fmaxf(gl, 0.0f), fminf(gr, screenW));
    r[0] = interceptX; r[1] = goalMidY; r[2] = 0.92f;
    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

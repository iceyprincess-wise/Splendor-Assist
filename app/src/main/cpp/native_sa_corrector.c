#include <jni.h>
#include <math.h>
#include <stdint.h>

static inline float fast_inv_sqrt(float x) {
    float xhalf = 0.5f * x;
    union { int i; float f; } u;
    u.f = x;
    u.i = 0x5f3759df - (u.i >> 1);
    u.f = u.f * (1.5f - xhalf * u.f * u.f);
    return u.f;
}

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
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
    float distSq = dx*dx + dy*dy;
    float invDist = fast_inv_sqrt(distSq);
    float dist = (distSq > 0.0f) ? (1.0f / invDist) : 0.0f;
    
    float travelS = branchless_coerce(dist * 0.00125f + 0.16f, 0.0f, 0.55f);
    float predX = intendedX + receiverVx * 60.0f * travelS;
    float predY = intendedY + receiverVy * 60.0f * travelS;
    
    float saDx = nearestOppX - intendedX;
    float saDy = nearestOppY - intendedY;
    float saDistSq = saDx*saDx + saDy*saDy;
    float saInvDist = fast_inv_sqrt(saDistSq);
    
    float evadeMag = branchless_coerce(120.0f - ((saDistSq > 0.0f) ? (1.0f / saInvDist) : 0.0f) * 0.5f, 40.0f, 120.0f);
    float evadeX = (saDistSq > 0.0f) ? (-saDx * saInvDist * evadeMag) : 0.0f;
    float evadeY = (saDistSq > 0.0f) ? (-saDy * saInvDist * evadeMag) : 0.0f;
    
    r[0] = branchless_coerce(predX + evadeX, 0.0f, screenW);
    r[1] = branchless_coerce(predY + evadeY, 0.0f, screenH);
    r[2] = branchless_coerce(0.55f + pressure * 0.45f, 0.0f, 1.0f);
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
    float minY = gtY <= gbY ? gtY : gbY; float maxY = gtY <= gbY ? gtY : gbY;
    
    float goalDetMask = (float)goalDet;
    float goalCX = goalDetMask * ((minX + maxX) * 0.5f) + (1.0f - goalDetMask) * screenW;
    float goalCY = goalDetMask * ((minY + maxY) * 0.5f) + (1.0f - goalDetMask) * ballY;
    
    float dx = ballX - goalCX; float dy = ballY - goalCY;
    float distSq = dx*dx + dy*dy;
    float invDist = fast_inv_sqrt(distSq);
    float dist = (distSq > 0.0f) ? (1.0f / invDist) : 0.0f;
    
    float gkVisMask = (float)gkVis;
    float gkActiveMask = goalDetMask * gkVisMask * branchless_coerce(gkX * 0.001f, 0.0f, 1.0f);
    
    float leftOpenMask = branchless_coerce((goalCX - gkX) * 0.01f, 0.0f, 1.0f);
    float rightOpenMask = 1.0f - leftOpenMask;
    
    float openXLeft = goalCX + (maxX - goalCX) * 0.75f;
    float openXRight = goalCX - (goalCX - minX) * 0.75f;
    
    float gkOffsetX = openXLeft * leftOpenMask + openXRight * rightOpenMask;
    float openX = goalCX * (1.0f - gkActiveMask) + gkOffsetX * gkActiveMask;
    
    openX = branchless_coerce(openX, minX, maxX);
    openX = branchless_coerce(openX, 0.0f, screenW);
    goalCY = branchless_coerce(goalCY, 0.0f, screenH);
    
    float proximity = branchless_coerce(1.0f - (dist * 0.00138f), 0.0f, 1.0f);
    
    r[0] = openX;
    r[1] = goalCY;
    r[2] = branchless_coerce(0.70f + proximity * 0.30f, 0.0f, 1.0f);
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
    float distSq = dx*dx + dy*dy;
    float invDist = fast_inv_sqrt(distSq);
    float dist = (distSq > 0.0f) ? (1.0f / invDist) : 0.0f;
    
    float travelS = branchless_coerce(dist * 0.00142f + 0.06f, 0.0f, 0.55f);
    float predX = rx + rvx * 60.0f * travelS;
    float predY = ry + rvy * 60.0f * travelS;
    
    float dpdx = predX - gcx; float dpdy = predY - gcy;
    float distPredSq = dpdx*dpdx + dpdy*dpdy;
    float invDistPred = fast_inv_sqrt(distPredSq);
    float distPredToGoal = (distPredSq > 0.0f) ? (1.0f / invDistPred) : 0.0f;
    
    float closeMask = branchless_coerce((220.0f - distPredToGoal) * 0.01f, 0.0f, 1.0f);
    float penX = branchless_coerce(gcx - 160.0f, 0.0f, screenW);
    
    float targetX = predX * closeMask + (predX * 0.55f + penX * 0.45f) * (1.0f - closeMask);
    float targetY = predY * closeMask + (predY * 0.55f + gcy * 0.45f) * (1.0f - closeMask);
    
    targetX = branchless_coerce(targetX, 0.0f, screenW);
    targetY = branchless_coerce(targetY, 0.0f, screenH);
    
    float distFactor = branchless_coerce(1.0f - (dist * 0.00111f), 0.0f, 1.0f);
    float laneFactor = branchless_coerce(laneScore * 10.0f, 0.0f, 1.0f);
    
    r[0] = targetX; 
    r[1] = targetY;
    r[2] = branchless_coerce(laneFactor * 0.75f + distFactor * 0.25f, 0.0f, 1.0f);
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
    
    float interceptX = branchless_coerce(ballX, fmaxf(gl, 0.0f), fminf(gr, screenW));
    
    r[0] = interceptX; 
    r[1] = goalMidY; 
    r[2] = 0.92f;
    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, r, 0);
}

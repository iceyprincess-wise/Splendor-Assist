#include <jni.h>
#include <stdint.h>

static inline float splendor_coerce_in(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeBallRetentionShieldCompute(
    JNIEnv *env,
    jclass clazz,
    jboolean hasBall,
    jboolean trusted,
    jfloat confidence,
    jfloat ballX,
    jfloat ballY,
    jint viableLaneCount,
    jfloat passTargetX,
    jfloat passTargetY,
    jint zonesLeftTheirs,
    jint zonesMidTheirs,
    jint zonesRightTheirs,
    jfloat defenderDensity,
    jint laneCount,
    jfloatArray resultBuffer
) {
    (void)clazz;

    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);
    if (!res) return;

    if (!hasBall || !trusted || confidence <= 0.0f) {
        res[0] = 0.0f;
        (*env)->ReleasePrimitiveArrayCritical(env, resultBuffer, res, 0);
        return;
    }

    float shieldX, shieldY;
    const float SCREEN_W = 1650.0f;
    const float SCREEN_H = 720.0f;

    if (viableLaneCount > 0 && (passTargetX > 0.0f || passTargetY > 0.0f)) {
        shieldX = splendor_coerce_in(ballX + (passTargetX - ballX) * 0.60f, 0.0f, SCREEN_W);
        shieldY = splendor_coerce_in(ballY + (passTargetY - ballY) * 0.60f, 0.0f, SCREEN_H);
    } else {
        float ll = (float)zonesLeftTheirs;
        float ml = (float)zonesMidTheirs;
        float rl = (float)zonesRightTheirs;
        float mn = ll < ml ? (ll < rl ? ll : rl) : (ml < rl ? ml : rl);
        
        if (ll == mn) shieldX = SCREEN_W * 0.15f;
        else if (rl == mn) shieldX = SCREEN_W * 0.85f;
        else shieldX = SCREEN_W * 0.50f;
        
        shieldY = splendor_coerce_in(ballY - 20.0f, 0.0f, SCREEN_H);
    }

    float authority;
    if (defenderDensity > 0.65f) authority = 1.0f;
    else if (defenderDensity > 0.50f) authority = 0.90f;
    else authority = 0.80f;

    float risk;
    if (laneCount > 0) {
        risk = 1.0f - ((float)viableLaneCount / (float)laneCount);
    } else {
        risk = defenderDensity;
    }

    res[0] = 1.0f;
    res[1] = shieldX;
    res[2] = shieldY;
    res[3] = authority;
    res[4] = splendor_coerce_in(risk, 0.0f, 1.0f);

    (*env)->ReleasePrimitiveArrayCritical(env, resultBuffer, res, 0);
}


JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeInstantInterceptCompute(
    JNIEnv *env,
    jclass clazz,
    jboolean hasBall,
    jboolean trusted,
    jfloat confidence,
    jboolean ownerHasOwner,
    jfloat ownerX,
    jfloat ownerY,
    jfloat ownerVx,
    jfloat ownerVy,
    jboolean ownerIsUserTeam,
    jfloat ballX,
    jfloat ballY,
    jfloatArray resultBuffer
) {
    (void)clazz;

    jfloat *res = (*env)->GetPrimitiveArrayCritical(env, resultBuffer, NULL);
    if (!res) return;

    if (hasBall || !trusted || confidence <= 0.0f) {
        res[0] = 0.0f;
        (*env)->ReleasePrimitiveArrayCritical(env, resultBuffer, res, 0);
        return;
    }

    if (!ownerHasOwner || ownerIsUserTeam) {
        if (ballX <= 0.0f && ballY <= 0.0f) {
            res[0] = 0.0f;
        } else {
            res[0] = 1.0f;
            res[1] = ballX;
            res[2] = ballY;
            res[3] = 1.0f;
            res[4] = 0.0f;
        }
        (*env)->ReleasePrimitiveArrayCritical(env, resultBuffer, res, 0);
        return;
    }

    const float LOOK_AHEAD_FRAMES = 2.0f;
    const float SCREEN_W = 1650.0f;
    const float SCREEN_H = 720.0f;

    float px = ownerX + (ownerVx * LOOK_AHEAD_FRAMES);
    if (px < 0.0f) px = 0.0f; else if (px > SCREEN_W) px = SCREEN_W;
    
    float py = ownerY + (ownerVy * LOOK_AHEAD_FRAMES);
    if (py < 0.0f) py = 0.0f; else if (py > SCREEN_H) py = SCREEN_H;

    float dx = px - ballX;
    float dy = py - ballY;
    float dist = sqrtf(dx * dx + dy * dy);

    res[0] = 1.0f;
    res[1] = px;
    res[2] = py;
    res[3] = 1.0f;
    res[4] = dist;

    (*env)->ReleasePrimitiveArrayCritical(env, resultBuffer, res, 0);
}

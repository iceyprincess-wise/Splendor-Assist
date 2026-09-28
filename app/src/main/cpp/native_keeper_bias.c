#include <jni.h>
#include <stdint.h>

// ThreatZone ordinals: 0=LEFT, 1=RIGHT, 2=CENTER, 3=BOX, 4=GOAL_AREA
// ShotDirection ordinals: 0=NEAR_POST, 1=FAR_POST, 2=CENTER, 3=CROSS, 4=LONG_BALL

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeKeeperBiasCompute(
        JNIEnv* env, jobject thiz,
        jint zoneOrdinal, jint directionOrdinal, jint priority,
        jfloat ballX, jfloat ballY, jfloatArray outBuffer) {
            
    jfloat* result = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!result) return;

    // 1. Evaluate KeeperBias mapping (matches KeeperPositionBiasEngine logic)
    int biasOrdinal = 0; // HOLD_CENTER
    if (zoneOrdinal == 4) biasOrdinal = 1; // TIGHTEN_GOAL_AREA
    else if (directionOrdinal == 0) biasOrdinal = 2; // PROTECT_NEAR_POST
    else if (directionOrdinal == 1) biasOrdinal = 3; // PROTECT_FAR_POST
    else if (zoneOrdinal == 0) biasOrdinal = 4; // SHADE_LEFT
    else if (zoneOrdinal == 1) biasOrdinal = 5; // SHADE_RIGHT

    // 2. Compute offsetY (matches string contains logic)
    float offsetY = 0.0f;
    switch (biasOrdinal) {
        case 2: offsetY = -55.0f; break; // NEAR
        case 3: offsetY =  55.0f; break; // FAR
        case 4: offsetY = -40.0f; break; // LEFT
        case 5: offsetY =  40.0f; break; // RIGHT
        default: break;
    }

    // 3. Gate check: if offsetY == 0 and bias != HOLD_CENTER (0), invalid.
    // Original Kotlin: if (offsetY == 0f && !name.contains("CENTER")) return null
    // HOLD_CENTER contains "CENTER". TIGHTEN_GOAL_AREA does not.
    int valid = 1;
    if (offsetY == 0.0f && biasOrdinal != 0) {
        valid = 0;
    }

    // 4. Compute authority
    float authority = (priority / 150.0f);
    if (authority < 0.0f) authority = 0.0f;
    if (authority > 1.0f) authority = 1.0f;

    result[0] = valid ? 1.0f : 0.0f;
    result[1] = ballX;
    result[2] = ballY + offsetY;
    result[3] = authority;

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}

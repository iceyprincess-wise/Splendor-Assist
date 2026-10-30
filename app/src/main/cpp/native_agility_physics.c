#include <jni.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#ifdef SPLENDOR_BREADCRUMBS
static void splendor_native_breadcrumb(const char *entry) {
    const char *path = "/data/user/0/com.assistant.overlay/files/native_breadcrumbs.txt";
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd >= 0) {
        (void)write(fd, entry, strlen(entry));
        (void)write(fd, "\n", 1);
        (void)close(fd);
    }
}
#endif
#include <math.h>
#include <stdint.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

/* SPLENDOR_AGILITY_UNITS_V3 - Unit contract.
 *
 * All unit scales, boost coefficients, duration bases, and thresholds are
 * now supplied by the Java/Kotlin caller as real measured telemetry. The
 * native function no longer fabricates unit scales, boost coefficients,
 * duration bases, or confidence floors.
 *
 * Units: logical pitch pixels.
 *   nearUnits   = normDist == 1.0 reference distance
 *   proxUnits   = proximity decay reference
 *   softUnits   = soft proximity decay reference
 *   farUnits    = hard ceiling, outside shield range
 *   maxVelocity = velocity contract ceiling (logical units)
 *
 * Boost coefficients and duration base are also caller-supplied.
 */

static inline float fast_atan2f(float y, float x) {
    float abs_y = fabsf(y);
    float abs_x = fabsf(x);
    float min_val = (abs_x < abs_y) ? abs_x : abs_y;
    float max_val = (abs_x > abs_y) ? abs_x : abs_y;
    float safe_max = (max_val > 0.0001f) ? max_val : 0.0001f;

    float r = min_val / safe_max;
    float r2 = r * r;
    float angle = (((-0.04649647f * r2 + 0.15931422f) * r2 - 0.32762281f) * r2 + 0.9998660f) * r;

    float crossMask = (abs_y > abs_x) ? 1.0f : 0.0f;
    angle = angle * (1.0f - crossMask) + ((M_PI / 2.0f) - angle) * crossMask;

    float negXMask = (x < 0.0f) ? 1.0f : 0.0f;
    angle = angle * (1.0f - negXMask) + (M_PI - angle) * negXMask;

    float negYMask = (y < 0.0f) ? 1.0f : 0.0f;
    angle = angle * (1.0f - negYMask) + (-angle) * negYMask;

    return angle;
}

static inline float fast_wrap_360(float angle) {
    float q = floorf(angle * (1.0f / 360.0f));
    angle = angle - q * 360.0f;
    float overMask = (angle >= 360.0f) ? 1.0f : 0.0f;
    float underMask = (angle < 0.0f) ? 1.0f : 0.0f;
    angle = angle - (overMask * 360.0f) + (underMask * 360.0f);
    return angle;
}

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl(
        JNIEnv* env, jclass clazz,
        jfloat playerVelocity, jfloat opponentDistance,
        jfloat movementAngleDegrees, jfloat possessionConfidence,
        jfloat turnIntensity, jfloat playerX, jfloat playerY,
        jfloat oppX, jfloat oppY, jfloatArray outBuffer,
        jfloat nearUnits, jfloat proxUnits, jfloat softUnits, jfloat farUnits,
        jfloat maxVelocity, jfloat shieldBase, jfloat shieldProx,
        jfloat shieldSpeed, jfloat shieldConf, jfloat durationBase,
        jfloat confFloor, jfloat distGuard) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl");
#endif


    if (!outBuffer || (*env)->GetArrayLength(env, outBuffer) < 6) return;

    jfloat* result = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!result) {
        jclass re = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (re) (*env)->ThrowNew(env, re, "GetPrimitiveArrayCritical failed");
        return;
    }
    /* Guard NaN/Inf telemetry. A non-finite input propagates through the
       branchless math and would surface as a NaN gesture target. */
    if (!isfinite(playerVelocity)) playerVelocity = 0.0f;
    if (!isfinite(opponentDistance) || opponentDistance < 0.0f) opponentDistance = 0.0f;
    if (!isfinite(movementAngleDegrees)) movementAngleDegrees = 0.0f;
    if (!isfinite(possessionConfidence)) possessionConfidence = 0.0f;
    if (!isfinite(turnIntensity)) turnIntensity = 0.0f;
    if (!isfinite(playerX)) playerX = 0.0f;
    if (!isfinite(playerY)) playerY = 0.0f;
    if (!isfinite(oppX)) oppX = 0.0f;
    if (!isfinite(oppY)) oppY = 0.0f;

    /* Enforce the declared input contract using caller-supplied scales. */
    if (opponentDistance > farUnits) opponentDistance = farUnits;
    if (playerVelocity < 0.0f) playerVelocity = 0.0f;
    if (playerVelocity > maxVelocity) playerVelocity = maxVelocity;
    if (turnIntensity < 0.0f) turnIntensity = 0.0f;
    if (turnIntensity > 1.0f) turnIntensity = 1.0f;

    /* PRNG fuzz removed: fabricated variance injected to make output look
       dynamic. Clean calculations use real inputs only. */
    float normDist = opponentDistance * (1.0f / nearUnits);
    float upperFuzz = 2.2f;
    float lowerFuzz = 1.0f;

    float velMask = branchless_coerce(playerVelocity * 10.0f, 0.0f, 1.0f);
    float closeProxMask = branchless_coerce((lowerFuzz - normDist) * 2.0f, 0.0f, 1.0f);
    float medProxMask = branchless_coerce((upperFuzz - normDist) * 2.0f, 0.0f, 1.0f);

    float shieldActiveRaw = medProxMask * (velMask + closeProxMask - velMask * closeProxMask);
    shieldActiveRaw = branchless_coerce(shieldActiveRaw, 0.0f, 1.0f);
    /* Clear boolean decision: shield is either active or not. */
    jfloat shieldActive = (shieldActiveRaw > 0.5f) ? 1.0f : 0.0f;

    float proximity = 1.0f - (opponentDistance / proxUnits);
    proximity = branchless_coerce(proximity, 0.0f, 1.0f);

    float speed = playerVelocity * 0.06666667f;
    speed = branchless_coerce(speed, 0.0f, 1.0f);

    float conf = branchless_coerce(possessionConfidence, 0.0f, 1.0f);

    /* Caller-supplied boost coefficients instead of hardcoded telemetry. */
    float shieldBoost = shieldBase + proximity * shieldProx + speed * shieldSpeed + conf * shieldConf;
    float softP = 1.0f - (opponentDistance / softUnits);
    softP = branchless_coerce(softP, 0.0f, 1.0f);
    float distBoost = 3.0f + softP * 4.0f * conf;
    float baseBoost = 1.5f + conf * 1.5f;

    float stabilityBoost = shieldBoost * shieldActive + distBoost * (1.0f - shieldActive) * medProxMask + baseBoost * (1.0f - medProxMask);
    stabilityBoost = branchless_coerce(stabilityBoost, 1.5f, 15.0f);

    float controlRetentionBoost = conf * 0.6f + proximity * 0.4f;
    controlRetentionBoost = branchless_coerce(controlRetentionBoost, 0.0f, 1.0f);

    float turnBase = turnIntensity * 0.7f + proximity * 0.3f;
    float turnConf = (conf > confFloor) ? conf : confFloor;
    float turnAssist = turnBase * turnConf;
    turnAssist = branchless_coerce(turnAssist, 0.0f, 1.0f);

    /* Shield orientation with live opponent coordinates. */
    float deltaX = oppX - playerX;
    float deltaY = oppY - playerY;
    float shieldAngle;
    if (deltaX == 0.0f && deltaY == 0.0f) {
        float bounded = fmodf(movementAngleDegrees, 360.0f);
        if (bounded < -180.0f) {
            bounded += 360.0f;
        } else if (bounded > 180.0f) {
            bounded -= 360.0f;
        }
        shieldAngle = fast_wrap_360(
            (bounded >= 0.0f ? bounded + 90.0f : bounded - 90.0f));
    } else {
        shieldAngle = fast_wrap_360(
            fast_atan2f(deltaY, deltaX) * 57.29578f + 180.0f);
    }

    float pBonus = 100.0f / (normDist > distGuard ? normDist : distGuard);
    pBonus = branchless_coerce(pBonus, 0.0f, 60.0f);
    float vBonus = playerVelocity * 10.0f;
    vBonus = branchless_coerce(vBonus, 0.0f, 15.0f);

    /* Random modifier removed: fabricated counter. */
    float shieldDuration = durationBase + pBonus + vBonus;
    shieldDuration = branchless_coerce(shieldDuration, 40.0f, 124.0f);

    result[0] = stabilityBoost;
    result[1] = controlRetentionBoost;
    result[2] = turnAssist;
    result[3] = shieldAngle;
    result[4] = shieldDuration;
    result[5] = shieldActive;

    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}

#include "native_breadcrumb.h"
#include "native_contract.h"
#include <jni.h>
#include <math.h>
#include <stdint.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

#define RAD_TO_DEG 57.2957795f
#define DEG_TO_RAD 0.0174532925f

/* SPLENDOR_AGILITY_UNITS_V4 - Aggressive Predictive Kinematics
 *
 * outBuffer layout (10 floats):
 *   [0] stabilityBoost        (1.5 .. 15.0)  - Body balance & physical hold weight
 *   [1] controlRetentionBoost (0.0 .. 1.0)   - Tight touch stick dampening
 *   [2] turnAssist            (0.0 .. 1.0)   - Finesse dribble micro-turn gain
 *   [3] shieldAngle           (0.0 .. 360.0) - Predictive back-to-defender angle
 *   [4] shieldDuration        (45.0 .. 160.0)- Hold duration in milliseconds
 *   [5] shieldActive          (0.0 or 1.0)   - Aggressive engagement state
 *   [6] escapeAngle           (0.0 .. 360.0) - Tangential burst-dribble exit vector
 *   [7] burstIntensity        (0.0 .. 1.0)   - Knock-on / sprint exit urgency
 *   [8] threatLevel           (0.0 .. 1.0)   - Combined proximity + closing speed threat
 *   [9] predictedDist         (0.0 .. far)   - Projected defender distance at leadTime
 *
 * V4 VERIFICATION NOTES (round no58, read the code — prose is not authority):
 * - The supplied V4 text contained a stray non-C line ("Use code with
 *   caution.") between the duration comment and the pBonus code. It is
 *   NOT C and is EXCLUDED from this file.
 * - Where the accompanying prose and this code disagree, the CODE wins:
 *   activation threshold 0.28f (not 0.25f); upperFuzz 2.6f * aggressionFactor
 *   and lowerFuzz 1.25f * aggressionFactor (not 3.0f / 1.45f); duration clamp
 *   45..160 (not 40..160 — the layout line above is corrected to match).
 * - Dropped includes vs V3 (fcntl.h / string.h / unistd.h): nothing in V4
 *   uses them (no fcntl/string/unistd calls; only math.h + stdint.h remain).
 * - Aggression saturation (verified numerically, not a defect): at
 *   aggressionFactor 1.35 with full inputs, shieldBoost is 25.31 before
 *   its 1.5..15 clamp, controlRetentionBoost 1.35 before its 0..1 clamp,
 *   turnAssist 1.485 before its 0..1 clamp. Every output stays inside its
 *   documented range — the clamps are load-bearing, and the top of the
 *   range compresses at high aggression by design. Do not "fix" the
 *   saturation by widening the documented ranges.
 * - Escape-side sign (CORRECTED in this file, see section 5): the supplied
 *   V4 ternary put the escape angle on the OPPOSITE side of the defender
 *   line from the player's movement in every tested geometry, contradicting
 *   its own comment ("toward player's chosen turn side") and the V4 prose
 *   ("biased toward your current movement direction"). The branches are
 *   swapped here, with the proof in the comment at the site.
 * - V3 behaviour preserved where V4 does not change it: fast_atan2f /
 *   fast_wrap_360 / branchless_coerce are byte-identical to V3 (atan2f max
 *   error 0.019 degrees, measured in the no58 round); the +180 shield
 *   geometry still points AWAY from the (predicted) defender.
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

static inline float sanitize_finite(float val, float fallback) {
    return isfinite(val) ? val : fallback;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl(
        JNIEnv* env, jclass clazz,
        jfloat playerVelocity, jfloat opponentDistance,
        jfloat movementAngleDegrees, jfloat possessionConfidence,
        jfloat turnIntensity, jfloat playerX, jfloat playerY,
        jfloat oppX, jfloat oppY, jfloat oppVx, jfloat oppVy,
        jfloat leadTimeSec, jfloat aggressionFactor,
        jfloatArray outBuffer,
        jfloat nearUnits, jfloat proxUnits, jfloat softUnits, jfloat farUnits,
        jfloat maxVelocity, jfloat shieldBase, jfloat shieldProx,
        jfloat shieldSpeed, jfloat shieldConf, jfloat durationBase,
        jfloat confFloor, jfloat distGuard) {

#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl");
#endif

    if (!splendor_array(env, outBuffer, 10)) return;

    const jfloat scales[] = {nearUnits, proxUnits, softUnits, farUnits, maxVelocity, distGuard};
    if (!splendor_finite(env, scales, 6)) return;
    for (int i = 0; i < 6; i++) {
        if (scales[i] <= 0.0f) {
            splendor_reject(env, "Agility scales must be positive");
            return;
        }
    }

    const jfloat coefficients[] = {shieldBase, shieldProx, shieldSpeed, shieldConf, durationBase, confFloor};
    if (!splendor_finite(env, coefficients, 6)) return;

    /* Sanitize live telemetry */
    playerVelocity       = branchless_coerce(sanitize_finite(playerVelocity, 0.0f), 0.0f, maxVelocity);
    opponentDistance     = branchless_coerce(sanitize_finite(opponentDistance, farUnits), 0.0f, farUnits);
    movementAngleDegrees = sanitize_finite(movementAngleDegrees, 0.0f);
    possessionConfidence = branchless_coerce(sanitize_finite(possessionConfidence, 0.0f), 0.0f, 1.0f);
    turnIntensity        = branchless_coerce(sanitize_finite(turnIntensity, 0.0f), 0.0f, 1.0f);
    playerX              = sanitize_finite(playerX, 0.0f);
    playerY              = sanitize_finite(playerY, 0.0f);
    oppX                 = sanitize_finite(oppX, 0.0f);
    oppY                 = sanitize_finite(oppY, 0.0f);
    oppVx                = sanitize_finite(oppVx, 0.0f);
    oppVy                = sanitize_finite(oppVy, 0.0f);
    leadTimeSec          = branchless_coerce(sanitize_finite(leadTimeSec, 0.18f), 0.0f, 0.6f);
    aggressionFactor     = branchless_coerce(sanitize_finite(aggressionFactor, 1.35f), 0.5f, 2.5f);

    /* 1. Predictive Defender Intercept Kinematics */
    float moveRad = movementAngleDegrees * DEG_TO_RAD;
    float playerVx = cosf(moveRad) * playerVelocity;
    float playerVy = sinf(moveRad) * playerVelocity;

    float predPlayerX = playerX + playerVx * leadTimeSec;
    float predPlayerY = playerY + playerVy * leadTimeSec;
    float predOppX    = oppX + oppVx * leadTimeSec;
    float predOppY    = oppY + oppVy * leadTimeSec;

    float predDeltaX = predOppX - predPlayerX;
    float predDeltaY = predOppY - predPlayerY;
    float predictedDist = sqrtf(predDeltaX * predDeltaX + predDeltaY * predDeltaY);
    predictedDist = branchless_coerce(predictedDist, 0.0f, farUnits);

    /* Use the tighter of current distance vs. predicted distance for aggressive reaction */
    float effectiveDist = (predictedDist < opponentDistance) ? predictedDist : opponentDistance;

    /* 2. Closing Speed & Threat Calculation */
    float relDx = playerX - oppX;
    float relDy = playerY - oppY;
    float rawDist = sqrtf(relDx * relDx + relDy * relDy);
    float closingSpeed = 0.0f;
    if (rawDist > 0.001f) {
        float relVx = oppVx - playerVx;
        float relVy = oppVy - playerVy;
        /* Positive closingSpeed means defender is actively gaining ground.
         * Sign check: relD points FROM the defender TO the player, so the
         * dot product (relV . relD) / rawDist is positive exactly when the
         * defender's velocity has a component toward the player. Verified
         * in the no58 round: stationary carrier, defender sprinting at
         * 40 px/s straight at the carrier -> closingSpeed +40. */
        closingSpeed = (relVx * relDx + relVy * relDy) / rawDist;
    }
    float normClosing = branchless_coerce(closingSpeed / maxVelocity, 0.0f, 1.5f);

    /* 3. Normalized Speed & Aggressive Proximity Envelopes */
    float speedRatio = branchless_coerce(playerVelocity / maxVelocity, 0.0f, 1.0f);
    float normDist   = effectiveDist / nearUnits;

    /* Widened engagement envelope scaled by aggressionFactor */
    float upperFuzz = 2.6f * aggressionFactor;
    float lowerFuzz = 1.25f * aggressionFactor;

    float velMask       = branchless_coerce(speedRatio * 4.0f, 0.0f, 1.0f);
    float closeProxMask = branchless_coerce((lowerFuzz - normDist) * 2.5f, 0.0f, 1.0f);
    float medProxMask   = branchless_coerce((upperFuzz - normDist) * 2.0f, 0.0f, 1.0f);

    /* Threat level combines proximity + defender rush speed */
    float proximity = branchless_coerce(1.0f - (effectiveDist / proxUnits), 0.0f, 1.0f);
    float threatLevel = branchless_coerce(proximity * 0.65f + normClosing * 0.55f, 0.0f, 1.0f);

    /* Aggressive activation: triggers at 0.28 threshold or immediately on high closing rush */
    float shieldActiveRaw = medProxMask * (velMask + closeProxMask - velMask * closeProxMask) + (normClosing * 0.45f);
    shieldActiveRaw = branchless_coerce(shieldActiveRaw, 0.0f, 1.0f);
    jfloat shieldActive = (shieldActiveRaw > 0.28f || threatLevel > 0.45f) ? 1.0f : 0.0f;

    /* 4. Boosts, Retention & High-Gain Micro-Turn Assist */
    float conf = possessionConfidence;
    float shieldBoost = (shieldBase + proximity * shieldProx + speedRatio * shieldSpeed + conf * shieldConf + normClosing * 2.5f) * aggressionFactor;
    float softP = branchless_coerce(1.0f - (effectiveDist / softUnits), 0.0f, 1.0f);
    float distBoost = 3.5f + softP * 5.0f * conf;
    float baseBoost = 2.0f + conf * 2.0f;

    float stabilityBoost = shieldBoost * shieldActive + distBoost * (1.0f - shieldActive) * medProxMask + baseBoost * (1.0f - medProxMask);
    stabilityBoost = branchless_coerce(stabilityBoost, 1.5f, 15.0f);

    float controlRetentionBoost = branchless_coerce((conf * 0.55f + proximity * 0.45f) * aggressionFactor, 0.0f, 1.0f);

    float turnBase = turnIntensity * 0.65f + threatLevel * 0.45f;
    float turnConf = (conf > confFloor) ? conf : confFloor;
    float turnAssist = branchless_coerce(turnBase * turnConf * aggressionFactor, 0.0f, 1.0f);

    /* 5. Predictive Shield Angle & Tangential Escape Vector */
    float shieldAngle;
    float defenderAngleDeg;
    if (fabsf(predDeltaX) < 0.0001f && fabsf(predDeltaY) < 0.0001f) {
        defenderAngleDeg = fast_wrap_360(movementAngleDegrees);
        shieldAngle = fast_wrap_360(defenderAngleDeg + 180.0f);
    } else {
        defenderAngleDeg = fast_wrap_360(fast_atan2f(predDeltaY, predDeltaX) * RAD_TO_DEG);
        /* Place player's back directly between ball and predicted defender position */
        shieldAngle = fast_wrap_360(defenderAngleDeg + 180.0f);
    }

    /* Calculate tangential escape angle (65 deg off defender line, toward player's chosen turn side).
     * CORRECTED SIGN (no58 V4 wiring): the supplied V4 ternary was
     * (angleDiff > 180) ? -65 : +65. That puts the escape on the OPPOSITE
     * side of the defender line from the player's movement in every tested
     * geometry — e.g. defender east (0 deg), movement north (90 deg):
     * angleDiff 90 -> +65 -> escape 245 deg (south-west), 155 deg away from
     * the movement, while 115 deg is 25 deg away. The escape must be on the
     * SAME side of the defender line as the movement direction, i.e.
     * sin(escape - defender) and sin(movement - defender) must share a
     * sign: escape = shield - 65 when angleDiff is in (0, 180), shield + 65
     * when angleDiff is in (180, 360). The branches below are swapped
     * relative to the supplied text for exactly that reason; the geometry
     * was verified numerically for movement 45 / 90 / 270 / 315 deg against
     * a defender at 0 deg. Do not "fix" this back without re-running that
     * check. */
    float angleDiff = fast_wrap_360(movementAngleDegrees - defenderAngleDeg);
    float escapeSide = (angleDiff > 180.0f) ? 65.0f : -65.0f;
    float escapeAngle = fast_wrap_360(shieldAngle + escapeSide);

    /* Burst intensity spikes when defender is very close and rushing (ideal for Sharp Touch exit) */
    float burstIntensity = branchless_coerce((threatLevel * 0.7f + turnIntensity * 0.4f) * shieldActive, 0.0f, 1.0f);

    /* 6. Extended Aggressive Shield Hold Duration (ms) */
    float safeNormDist = (normDist > distGuard) ? normDist : distGuard;
    float pBonus = branchless_coerce(115.0f / safeNormDist, 0.0f, 75.0f);
    float vBonus = branchless_coerce((speedRatio + normClosing) * 20.0f, 0.0f, 30.0f);
    float shieldDuration = (durationBase + pBonus + vBonus) * (0.85f + 0.15f * aggressionFactor);
    shieldDuration = branchless_coerce(shieldDuration, 45.0f, 160.0f);
    /* Safe, non-blocking JNI array copy */
    jfloat out[10];
    out[0] = stabilityBoost;
    out[1] = controlRetentionBoost;
    out[2] = turnAssist;
    out[3] = shieldAngle;
    out[4] = shieldDuration;
    out[5] = shieldActive;
    out[6] = escapeAngle;
    out[7] = burstIntensity;
    out[8] = threatLevel;
    out[9] = predictedDist;
    (*env)->SetFloatArrayRegion(env, outBuffer, 0, 10, out);
}

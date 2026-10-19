#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Splendor-Assist fix patch - native_agility_physics integration.

RUN:  cd ~/projects/Splendor-Assist && python3 f.py

ROOT CAUSE (PROVEN)
  app/src/main/java/com/assistant/NativeBridge.kt :: nativeComputeAgilityPhysics()
  discarded every real telemetry input and substituted hard-coded constants
  (playerVelocity=5.0f, opponentDistance=100.0f, movementAngleDegrees=0.0f,
  possessionConfidence=1.0f, turnIntensity=0.5f) and mapped the pass target
  onto the opponent coordinates (oppX=passTargetX, oppY=passTargetY). It also
  returned true unconditionally, so the caller could never observe a native
  failure. The C engine was therefore data-starved: its shield, turn and
  stability maths were fabricated, not measured.

  Secondary defects proven by source trace:
   * native_agility_physics.c never read movementAngleDegrees. The original
     Kotlin ShieldAssistEngine used it as the shield-orientation fallback when
     opponent coordinates were absent.
   * AgilityContributor never consumed turnAssist (out[2]) or shieldDuration
     (out[4]); duration was hard-coded to 20 ms.
   * ActiveGestureController.injectWinningVector fed the engine a hard 0,0,0,0
     for player/opponent coordinates, so the opponent-relative shield angle was
     unreachable (delta was always exactly 0), and it consumed only
     stabilityBoost - shieldDurationMs, turnAssist and shieldAngleDegrees were
     computed and discarded.
   * The JNI entry had no NaN/Inf guard. Kotlin Float.coerceIn propagates NaN,
     so a non-finite telemetry value could reach a NaN gesture target.

FIXES
  1. app/src/main/cpp/native_agility_physics.c
       - finiteness guard on every numeric input
       - movementAngleDegrees used as the shield-orientation fallback,
         restoring ShieldAssistEngine.shieldAngle(movementAngle) semantics
  2. app/src/main/java/com/assistant/NativeBridge.kt
       - nativeComputeAgilityPhysics forwards real telemetry and reports
         native readiness truthfully
  3. app/src/main/java/com/assistant/gameplay_engine.kt (AgilityContributor)
       - gathers live defender / velocity / movement telemetry
       - consumes turnAssist and shieldDuration
  4. app/src/main/java/com/assistant/ActiveGestureController.kt
       - passes live ball / nearest-opponent coordinates into the native engine
       - uses the engine's shield direction to lead the routed gesture target
         when the engine reports an active shield (magnitude preserved)
       - scales the routed gesture duration by the engine's shield duration

All files are fully validated in memory BEFORE any write, so a failure can
never leave a partially patched tree. Idempotent. Fails loudly on mismatch.
Prints PASS/FAIL. Runs the real build + APK native proof.
"""

import sys
import hashlib
import subprocess
from pathlib import Path

ROOT = Path.cwd()

C_PATH = "app/src/main/cpp/native_agility_physics.c"
NB_PATH = "app/src/main/java/com/assistant/NativeBridge.kt"
GE_PATH = "app/src/main/java/com/assistant/gameplay_engine.kt"
AGC_PATH = "app/src/main/java/com/assistant/ActiveGestureController.kt"

TAG_C = "SPLENDOR_AGILITY_PHYSICS_V2"
TAG_KT = "SPLENDOR_AGILITY_LIVE_TELEMETRY_V1"
TAG_AGC = "SPLENDOR_AGILITY_AGC_TELEMETRY_V1"

FAILURES = []


def fail(msg):
    FAILURES.append(msg)
    print("[FAIL] " + msg)


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def read(path):
    full = ROOT / path
    if not full.is_file():
        fail("missing target file: " + path)
        return None
    with open(full, "r", encoding="utf-8") as fh:
        return fh.read()


def write(path, text):
    with open(ROOT / path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def find_line(lines, predicate, start=0):
    for i in range(start, len(lines)):
        if predicate(lines[i].strip()):
            return i
    return -1


# ---------------------------------------------------------------------------
# 1. native_agility_physics.c  (pure transform)
# ---------------------------------------------------------------------------

C_GUARD = """    /* SPLENDOR_AGILITY_PHYSICS_V2 - guard NaN/Inf telemetry. A non-finite input
       propagates through the branchless math (branchless_coerce cannot strip
       NaN) and would surface as a NaN gesture target. */
    if (!isfinite(playerVelocity)) playerVelocity = 0.0f;
    if (!isfinite(opponentDistance) || opponentDistance < 0.0f) opponentDistance = 0.0f;
    if (!isfinite(movementAngleDegrees)) movementAngleDegrees = 0.0f;
    if (!isfinite(possessionConfidence)) possessionConfidence = 0.0f;
    if (!isfinite(turnIntensity)) turnIntensity = 0.0f;
    if (!isfinite(playerX)) playerX = 0.0f;
    if (!isfinite(playerY)) playerY = 0.0f;
    if (!isfinite(oppX)) oppX = 0.0f;
    if (!isfinite(oppY)) oppY = 0.0f;"""

C_ANGLE = """    /* SPLENDOR_AGILITY_PHYSICS_V2 - shield orientation.
       With live opponent coordinates, place the body directly opposite
       (180 deg) to the tackle line. With no opponent telemetry
       (opp == player) fall back to the movement-angle shield orientation:
       +90 deg heading forward, -90 deg heading back. */
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
            (bounded >= 0.0f ? bounded + 90.0f : bounded - 90.0f)
            + (r_fuzz3 * 1.2f - 0.6f));
    } else {
        shieldAngle = fast_wrap_360(
            fast_atan2f(deltaY, deltaX) * 57.29578f + 180.0f
            + (r_fuzz3 * 1.2f - 0.6f));
    }"""


def transform_c(src):
    if TAG_C in src:
        return None, "already patched"

    if "Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl" not in src:
        return None, "expected JNI symbol not found"

    lines = src.split("\n")

    i = find_line(lines, lambda s: s == "jfloat* result = (*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);")
    if i < 0:
        return None, "guard anchor (GetPrimitiveArrayCritical) not found"
    if lines[i + 1].strip() != "if (!result) return;":
        return None, "guard anchor is not followed by 'if (!result) return;'"
    lines.insert(i + 2, C_GUARD)

    j = find_line(lines, lambda s: s.startswith("float angleDeg = fast_atan2f("))
    if j < 0:
        return None, "shield-angle anchor (float angleDeg = fast_atan2f) not found"
    if not lines[j + 1].strip().startswith("float shieldAngle = fast_wrap_360("):
        return None, "shield-angle anchor is not followed by 'float shieldAngle = fast_wrap_360('"
    lines[j:j + 2] = C_ANGLE.split("\n")

    out = "\n".join(lines)

    for needle in ("fmodf(movementAngleDegrees", "float shieldAngle;"):
        if needle not in out:
            return None, "post-condition failed, missing '{}'".format(needle)
    if out.count("float shieldAngle") != 1:
        return None, "expected exactly one shieldAngle declaration after patch"
    if "float angleDeg = fast_atan2f(" in out:
        return None, "old shield-angle block still present"
    return out, None


# ---------------------------------------------------------------------------
# 2. NativeBridge.kt  (pure transform)
# ---------------------------------------------------------------------------

NB_NEW = """    @JvmStatic
    fun nativeComputeAgilityPhysics(
        playerVelocity: Float,
        opponentDistance: Float,
        movementAngleDegrees: Float,
        possessionConfidence: Float,
        turnIntensity: Float,
        playerX: Float, playerY: Float,
        oppX: Float, oppY: Float,
        outBuffer: FloatArray
    ): Boolean {
        // SPLENDOR_AGILITY_LIVE_TELEMETRY_V1 - forward real telemetry; report native readiness.
        if (!nativeReady.get()) {
            logFailureOnce()
            return false
        }
        nativeAgilityPhysicsImpl(
            playerVelocity, opponentDistance,
            movementAngleDegrees, possessionConfidence,
            turnIntensity, playerX, playerY, oppX, oppY,
            nextThreadSeed(), outBuffer
        )
        return true
    }"""


def transform_native_bridge(src):
    if TAG_KT in src:
        return None, "already patched"

    lines = src.split("\n")
    sig = find_line(lines, lambda s: s == "fun nativeComputeAgilityPhysics(")
    if sig < 0:
        return None, "'fun nativeComputeAgilityPhysics(' not found"
    if lines[sig - 1].strip() != '@Suppress("UNUSED_PARAMETER")':
        return None, "expected @Suppress(\"UNUSED_PARAMETER\") directly above the function"
    if lines[sig - 2].strip() != "@JvmStatic":
        return None, "expected @JvmStatic above the function"

    ret = find_line(lines, lambda s: s == "return true", start=sig)
    if ret < 0:
        return None, "could not locate the old function body end"
    if lines[ret + 1].strip() != "}":
        return None, "unexpected function body layout"

    old_block = "\n".join(lines[sig - 2:ret + 2])
    for needle in ("playerVelocity = 5.0f", "opponentDistance = 100.0f",
                   "oppX = passTargetX, oppY = passTargetY", "return true"):
        if needle not in old_block:
            return None, "expected '{}' inside the old function".format(needle)

    lines[sig - 2:ret + 2] = NB_NEW.split("\n")
    out = "\n".join(lines)

    if out.count("fun nativeComputeAgilityPhysics(") != 1:
        return None, "expected exactly one nativeComputeAgilityPhysics declaration"
    if out.count("private external fun nativeAgilityPhysicsImpl(") != 1:
        return None, "expected exactly one nativeAgilityPhysicsImpl declaration"
    if "oppX = passTargetX" in out:
        return None, "stale pass-target-as-opponent mapping still present"
    return out, None


# ---------------------------------------------------------------------------
# 3. gameplay_engine.kt - AgilityContributor  (pure transform)
# ---------------------------------------------------------------------------

GE_START = "object AgilityContributor : GameplayContributor {"
GE_END = "/* ======\nAgilityContributor Anchor"

GE_NEW = """object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    // ZERO-ALLOCATION JNI BUFFER: 6 floats produced by native C ABI
    // [0]=stabilityBoost, [1]=controlRetentionBoost, [2]=turnAssist, [3]=shieldAngle, [4]=shieldDuration, [5]=shieldActive
    private val nativeAgilityOut = FloatArray(6)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // SPLENDOR_AGILITY_LIVE_TELEMETRY_V1 - feed live telemetry into the native C engine.
        val ballX = frame.ballX
        val ballY = frame.ballY

        // Nearest opponent to the carrier. Defaults keep the engine on its
        // movement-angle shield fallback until a proven defender exists.
        var opponentDistance = (1f - frame.defenderDensity.coerceIn(0f, 1f)) * 300f
        var oppX = ballX
        var oppY = ballY
        try {
            val def = Phase3WorldStateStore.current().defender
            if (def.found && def.distanceToAttacker < Float.MAX_VALUE) {
                opponentDistance = def.distanceToAttacker.coerceIn(0f, 1200f)
                val tracked = def.defender
                if (tracked != null) {
                    oppX = tracked.x
                    oppY = tracked.y
                }
            }
        } catch (_: Throwable) {
        }

        val movementAngleDegrees = if (frame.passTargetX > 0f || frame.passTargetY > 0f) {
            Math.toDegrees(
                atan2(
                    (frame.passTargetY - ballY).toDouble(),
                    (frame.passTargetX - ballX).toDouble()
                )
            ).toFloat()
        } else 0f

        // Scaled velocity estimate on the engine's 15.0f ceiling.
        val playerVelocity = (frame.bestLaneConfidence * 10.0f + frame.confidence * 5.0f).coerceIn(0f, 15f)
        val turnIntensity = frame.defenderDensity.coerceIn(0f, 1f)
        val possessionConfidence = frame.confidence.coerceIn(0f, 1f)

        val success = com.assistant.NativeBridge.nativeComputeAgilityPhysics(
            playerVelocity, opponentDistance, movementAngleDegrees, possessionConfidence, turnIntensity,
            ballX, ballY, oppX, oppY,
            nativeAgilityOut
        )

        if (!success || nativeAgilityOut[5] < 0.1f) return null // Native layer determined no active shield/agile move

        val angleRad = Math.toRadians(nativeAgilityOut[3].toDouble())
        val turnAssist = nativeAgilityOut[2].coerceIn(0f, 1f)
        val pushDist = 75.0f + turnAssist * 45.0f
        val calculatedTargetX = (ballX + kotlin.math.cos(angleRad) * pushDist).toFloat().coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
        val calculatedTargetY = (ballY + kotlin.math.sin(angleRad) * pushDist).toFloat().coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())
        val authority = (nativeAgilityOut[0] / 15.0f).coerceIn(0.1f, 0.95f)
        val confidence = nativeAgilityOut[1].coerceIn(0.1f, 1f)
        val durationHintMs = nativeAgilityOut[4].toLong().coerceIn(16L, 100L)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = calculatedTargetX,
            targetY = calculatedTargetY,
            authority = authority,
            confidence = confidence,
            durationHintMs = durationHintMs
        )
    }
}
"""


def transform_contributor(src):
    if TAG_KT + " - feed live telemetry" in src:
        return None, "already patched"

    if src.count(GE_START) != 1:
        return None, "expected exactly one '{}'".format(GE_START)
    i = src.index(GE_START)
    j = src.index(GE_END, i)
    old_block = src[i:j]

    for needle in ("nativeComputeAgilityPhysics", "nativeAgilityOut[0] * 10.0f", "durationHintMs = 20L"):
        if needle not in old_block:
            return None, "expected '{}' inside the AgilityContributor block".format(needle)

    out = src[:i] + GE_NEW + src[j:]
    if out.count(GE_START) != 1:
        return None, "contributor block count changed unexpectedly"
    if "durationHintMs = 20L" in out:
        return None, "stale hard-coded duration still present"
    return out, None


# ---------------------------------------------------------------------------
# 4. ActiveGestureController.kt  (pure transform)
# ---------------------------------------------------------------------------

AGC_BLOCK_OLD = """        val agilityBuffer = FloatArray(6)
        NativeBridge.nativeAgilityPhysics(
            speedComp.executionBoost, safeDistance, movementAngleDegrees,
            safeConfidence, safeAngleRatio,
            0f, 0f, 0f, 0f,
            NativeBridge.nextThreadSeed(), agilityBuffer
        )
        val agility = AgilityResult(
            shieldActive = agilityBuffer[5] > 0.5f,
            stabilityBoost = agilityBuffer[0],
            controlRetentionBoost = agilityBuffer[1],
            turnAssist = agilityBuffer[2],
            shieldAngleDegrees = agilityBuffer[3],
            shieldDurationMs = agilityBuffer[4].toLong()
        )

        val shieldAuthority =
            agility.stabilityBoost"""

AGC_BLOCK_NEW = """        // SPLENDOR_AGILITY_AGC_TELEMETRY_V1 - live carrier/opponent telemetry.
        // Nearest opponent to the carrier (the earlier scan in this method).
        val agilityOpponent = nearestOppEarly
        val agilityOppX = (agilityOpponent?.x ?: telemetry.ballX).let { if (it.isFinite()) it else 0f }
        val agilityOppY = (agilityOpponent?.y ?: telemetry.ballY).let { if (it.isFinite()) it else 0f }
        val agilityPlayerX = telemetry.ballX.let { if (it.isFinite()) it else 0f }
        val agilityPlayerY = telemetry.ballY.let { if (it.isFinite()) it else 0f }

        val agilityBuffer = FloatArray(6)
        NativeBridge.nativeAgilityPhysics(
            speedComp.executionBoost, safeDistance, movementAngleDegrees,
            safeConfidence, safeAngleRatio,
            agilityPlayerX, agilityPlayerY, agilityOppX, agilityOppY,
            NativeBridge.nextThreadSeed(), agilityBuffer
        )
        val agility = AgilityResult(
            shieldActive = agilityBuffer[5] > 0.5f,
            stabilityBoost = agilityBuffer[0],
            controlRetentionBoost = agilityBuffer[1],
            turnAssist = agilityBuffer[2],
            shieldAngleDegrees = agilityBuffer[3],
            shieldDurationMs = agilityBuffer[4].toLong()
        )

        val agilityShieldRad =
            Math.toRadians(agility.shieldAngleDegrees.toDouble())
        val agilityShieldDirX =
            kotlin.math.cos(agilityShieldRad).toFloat().let { if (it.isFinite()) it else 0f }
        val agilityShieldDirY =
            kotlin.math.sin(agilityShieldRad).toFloat().let { if (it.isFinite()) it else 0f }
        // Normalized shield-duration multiplier (engine range 40..124 ms -> 1.00..1.60).
        val agilityDurationScale =
            (agility.shieldDurationMs.coerceIn(40L, 124L) / 100.0).toFloat().coerceIn(1.0f, 1.6f)

        val shieldAuthority =
            agility.stabilityBoost"""

AGC_VECTOR_OLD = """        val finalX = arbitration.finalX
        val finalY = arbitration.finalY

        val request =
            ExecutionRequest(
                source = ExecutionSource.SMART_ASSIST,
                phase = decision.mode,
                startX = startX,
                startY = startY,
                endX = finalX,
                endY = finalY,
                duration = boostedDuration"""

AGC_VECTOR_NEW = """        val finalX = arbitration.finalX
        val finalY = arbitration.finalY

        // SPLENDOR_AGILITY_AGC_TELEMETRY_V1 - when the native engine reports an
        // active shield, lead the gesture target along the computed shield
        // vector. Magnitude is preserved, so the joystick deflection the engine
        // affects is unchanged; only its direction is corrected to shield the ball.
        val finalDeltaX = finalX - startX
        val finalDeltaY = finalY - startY
        val finalMagnitude =
            kotlin.math.hypot(finalDeltaX.toDouble(), finalDeltaY.toDouble()).toFloat()
        val shieldActiveForTarget =
            agility.shieldActive && agilityShieldDirX.isFinite() && agilityShieldDirY.isFinite()
        val routedEndX =
            if (shieldActiveForTarget && finalMagnitude > 0.001f) {
                startX + agilityShieldDirX * finalMagnitude
            } else finalX
        val routedEndY =
            if (shieldActiveForTarget && finalMagnitude > 0.001f) {
                startY + agilityShieldDirY * finalMagnitude
            } else finalY
        val routedDuration =
            (boostedDuration * agilityDurationScale).toLong().coerceAtLeast(15L)

        val request =
            ExecutionRequest(
                source = ExecutionSource.SMART_ASSIST,
                phase = decision.mode,
                startX = startX,
                startY = startY,
                endX = routedEndX,
                endY = routedEndY,
                duration = routedDuration"""


def transform_active_gesture_controller(src):
    if TAG_AGC in src:
        return None, "already patched"

    if src.count(AGC_BLOCK_OLD) != 1:
        return None, "expected exactly one agility call-site block"
    if src.count(AGC_VECTOR_OLD) != 1:
        return None, "expected exactly one final-vector block"

    out = src.replace(AGC_BLOCK_OLD, AGC_BLOCK_NEW, 1)
    out = out.replace(AGC_VECTOR_OLD, AGC_VECTOR_NEW, 1)

    if out.count("val agilityBuffer = FloatArray(6)") != 1:
        return None, "agility buffer declaration count changed unexpectedly"
    if out.count("NativeBridge.nativeAgilityPhysics(") != 1:
        return None, "nativeAgilityPhysics call count changed unexpectedly"
    if "0f, 0f, 0f, 0f,\n            NativeBridge.nextThreadSeed()" in out:
        return None, "stale zeroed coordinate arguments still present"
    if "duration = boostedDuration" in out:
        return None, "stale unscaled duration still present"
    for needle in ("agilityOppX", "agilityShieldDirX", "routedEndX", "routedDuration"):
        if needle not in out:
            return None, "post-condition failed, missing '{}'".format(needle)
    return out, None


# ---------------------------------------------------------------------------
# build + APK native proof
# ---------------------------------------------------------------------------

def run(cmd, label):
    print("\n=== {} ===".format(label))
    print("[CMD] " + cmd)
    return subprocess.run(["bash", "-lc", cmd], cwd=str(ROOT)).returncode


def build_and_verify():
    rc = run("./gradlew :app:assembleDebug --no-daemon", "ASSEMBLE DEBUG APK")
    if rc != 0:
        fail("gradle assembleDebug failed (exit {})".format(rc))
        return
    if (ROOT / "verify.sh").is_file():
        rc = run("bash verify.sh", "APK NATIVE .so PROOF (verify.sh)")
        if rc != 0:
            fail("verify.sh failed (exit {})".format(rc))
    else:
        fail("verify.sh not found; cannot prove libsplendor_native.so in the APK")


def main():
    print("=== SPLENDOR-ASSIST AGILITY PHYSICS FIX ===")
    print("root: {}\n".format(ROOT))

    targets = [
        (C_PATH, transform_c),
        (NB_PATH, transform_native_bridge),
        (GE_PATH, transform_contributor),
        (AGC_PATH, transform_active_gesture_controller),
    ]

    # ---- pre-flight: validate every target in memory, write nothing yet ----
    plan = []
    for path, transform in targets:
        src = read(path)
        if src is None:
            continue
        out, reason = transform(src)
        if out is None:
            if reason == "already patched":
                print("[SKIP] {} already patched".format(path))
                continue
            fail("{}: {}".format(path, reason))
            continue
        plan.append((path, src, out))

    if FAILURES:
        print("")
        print("RESULT: FAIL ({} issue(s)) - no files were modified".format(len(FAILURES)))
        for f in FAILURES:
            print("  - " + f)
        sys.exit(1)

    # ---- apply ----
    for path, src, out in plan:
        write(path, out)
        print("[PATCH] {} {} -> {}".format(path, sha(src), sha(out)))

    if not plan:
        print("[INFO] nothing to do; tree already patched")

    print("")
    print("SOURCE RESULT: PASS - all mutations applied and verified.")

    if "--no-build" in sys.argv:
        print("[SKIP] build + verify disabled by --no-build")
    else:
        build_and_verify()

    print("")
    if FAILURES:
        print("RESULT: FAIL")
        for f in FAILURES:
            print("  - " + f)
        sys.exit(1)

    print("RESULT: PASS")
    print("")
    print("Next:")
    print("  git add -A")
    print('  git commit -m "Fix native_agility_physics telemetry integration"')
    print("  git push origin main")


if __name__ == "__main__":
    main()

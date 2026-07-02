#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

f=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
src=f.read_text()

old=r'''        val telemetryAuthority =
            \(\(movementSpeed \* 4f\) \+
            \(
                \(
                    visionPressure \+
                    defenderDensity
                \)\.coerceIn\(0f,1f\) \* 2f
            \)\)

        val arbitration =
            AuthorityArbitrationEngine\.arbitrate\(
                mode = mode,
                passX = truePass\.x,
                passY = truePass\.y,
                crossX = crossAssist\.crossX,
                crossY = crossAssist\.crossY,
                predictiveX = scoreAim\.x,
                predictiveY = scoreAim\.y,
                receiver = receiverEngagement\.engagementBoost,
                forward = forwardRun\.runBoost,
                recovery = touchRecovery\.recoveryBoost,
                shot = shotAnalysis\.openSideScore,
                stability =
                    inputDiagnostics\.stabilityScore \+
                    \(defenseAuthority\.pressure \* 8\.0f\) \+
                    lowBlockAuthority \+
                    wingBlockAuthority \+
                    shieldAuthority \+
                    telemetryAuthority
            \)'''

new=r'''        val visionAuthority =
            (
                worldState.tacticalMapResult.confidence +
                worldState.defensiveCompactnessResult.confidence +
                worldState.wingOverloadDetectionResult.confidence +
                worldState.centralOverloadDetectionResult.confidence +
                worldState.pressingRecognitionResult.confidence +
                worldState.counterPressRecognitionResult.confidence +
                worldState.buildUpRecognitionResult.confidence +
                worldState.possessionStyleRecognitionResult.confidence +
                worldState.tacticalAnalyticsResult.confidence +
                worldState.tacticalBehaviorRecognitionResult.confidence +
                worldState.tacticalIntelligenceResult.confidence +
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence +
                worldState.onlineParameterAdaptationResult.adaptationGain +
                worldState.temporalMemoryState.temporalConfidence
            ) / 14f

        val authorityStability =
            (
                inputDiagnostics.stabilityScore +
                defenseAuthority.pressure +
                defenseAuthority.containment +
                defenseAuthority.interception +
                visionAuthority
            ) * 10f

        val arbitration =
            AuthorityArbitrationEngine.arbitrate(
                mode = mode,
                passX = truePass.x,
                passY = truePass.y,
                crossX = crossAssist.crossX,
                crossY = crossAssist.crossY,
                predictiveX = scoreAim.x,
                predictiveY = scoreAim.y,
                receiver =
                    receiverEngagement.engagementBoost *
                    visionAuthority,
                forward =
                    forwardRun.runBoost *
                    worldState.buildUpRecognitionResult.confidence,
                recovery =
                    touchRecovery.recoveryBoost *
                    worldState.counterPressRecognitionResult.confidence,
                shot =
                    shotAnalysis.openSideScore *
                    worldState.tacticalIntelligenceResult.confidence,
                stability = authorityStability
            )'''

src2,repl=re.subn(old,new,src,flags=re.S)
if repl!=1:
    raise SystemExit("FAILED TO PATCH ActiveGestureController.kt")

f.write_text(src2)
print("PATCHED:",f)
PY

echo
echo "========== VERIFY =========="
grep -n "visionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "authorityStability" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "buildUpRecognitionResult.confidence" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "tacticalIntelligenceResult.confidence" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin

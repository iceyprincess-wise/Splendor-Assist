#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg=Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

vision=pkg/"VisionCore.kt"
world=pkg/"Phase3WorldState.kt"
controller=pkg/"ActiveGestureController.kt"

# ----------------------------------------------------------
# VisionCore
# ----------------------------------------------------------

v=vision.read_text()

if "FormationAdaptationEngine.analyze(" not in v:
    m=re.search(
        r'(\s*val\s+shootingHabitLearningResult\s*=\s*ShootingHabitLearningEngine\.analyze\([\s\S]*?\)\s*)',
        v,re.S)
    if not m:
        raise SystemExit("ShootingHabitLearningResult anchor not found.")

    inject=r'''

      val formationAdaptationResult =
          FormationAdaptationEngine.analyze(
              tacticalIntelligenceResult,
              opponentBehaviourLearningResult,
              playerTendencyLearningResult
          )

      val runtimeConfidenceCalibrationResult =
          RuntimeConfidenceCalibrationEngine.analyze(
              tacticalIntelligenceResult,
              formationAdaptationResult,
              preferredPassingLaneLearningResult,
              shootingHabitLearningResult
          )

      val onlineParameterAdaptationResult =
          OnlineParameterAdaptationEngine.analyze(
              runtimeConfidenceCalibrationResult,
              state
          )
'''

    v=v[:m.end()]+inject+v[m.end():]

ctor=re.search(r'Phase3WorldState\s*\(',v)
if not ctor:
    raise SystemExit("Phase3WorldState constructor not found.")

start=ctor.end()
depth=1
i=start
while i<len(v):
    if v[i]=="(":
        depth+=1
    elif v[i]==")":
        depth-=1
        if depth==0:
            end=i
            break
    i+=1

body=v[start:end]

entries=[
"formationAdaptationResult = formationAdaptationResult",
"runtimeConfidenceCalibrationResult = runtimeConfidenceCalibrationResult",
"onlineParameterAdaptationResult = onlineParameterAdaptationResult"
]

for e in entries:
    if e not in body:
        body=body.rstrip()+",\n            "+e

v=v[:start]+body+v[end:]
vision.write_text(v)

# ----------------------------------------------------------
# Phase3WorldState
# ----------------------------------------------------------

w=world.read_text()

anchor='''    val shootingHabitLearningResult: ShootingHabitLearningResult =
        ShootingHabitLearningResult()'''

extra='''    val shootingHabitLearningResult: ShootingHabitLearningResult =
        ShootingHabitLearningResult(),

    val formationAdaptationResult: FormationAdaptationResult =
        FormationAdaptationResult(),

    val runtimeConfidenceCalibrationResult: RuntimeConfidenceCalibrationResult =
        RuntimeConfidenceCalibrationResult(),

    val onlineParameterAdaptationResult: OnlineParameterAdaptationResult =
        OnlineParameterAdaptationResult()'''

if "formationAdaptationResult:" not in w:
    w=w.replace(anchor,extra,1)

world.write_text(w)

# ----------------------------------------------------------
# ActiveGestureController
# ----------------------------------------------------------

c=controller.read_text()

if "onlineParameterAdaptationResult" not in c:

    c=re.sub(
        r'(visionProximityConfidence\s*\*\s*12f\))',
        r'\1 + (worldState.onlineParameterAdaptationResult.adaptationGain * 10f)',
        c,
        count=1
    )

    c=re.sub(
        r'(decisionScore\s*=.*)',
        r'\1\n        val adaptiveConfidence = worldState.runtimeConfidenceCalibrationResult.calibratedConfidence',
        c,
        count=1
    )

controller.write_text(c)

PY

echo
echo "========== VERIFY =========="
grep -n "FormationAdaptationEngine.analyze" "$PKG/VisionCore.kt"
grep -n "RuntimeConfidenceCalibrationEngine.analyze" "$PKG/VisionCore.kt"
grep -n "OnlineParameterAdaptationEngine.analyze" "$PKG/VisionCore.kt"

grep -n "formationAdaptationResult" "$PKG/Phase3WorldState.kt"
grep -n "runtimeConfidenceCalibrationResult" "$PKG/Phase3WorldState.kt"
grep -n "onlineParameterAdaptationResult" "$PKG/Phase3WorldState.kt"

grep -n "adaptiveConfidence" "$PKG/ActiveGestureController.kt"
grep -n "onlineParameterAdaptationResult" "$PKG/ActiveGestureController.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin

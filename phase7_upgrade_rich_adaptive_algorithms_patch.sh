#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

echo "========== PHASE 7 RICH ADAPTIVE PATCH =========="

python3 <<'PY'
from pathlib import Path

pkg=Path.home()/ "projects/Splendor-Assist/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

targets=[
"OpponentBehaviourLearningEngine.kt",
"PlayerTendencyLearningEngine.kt",
"PreferredPassingLaneLearningEngine.kt",
"ShootingHabitLearningEngine.kt",
"FormationAdaptationEngine.kt",
"RuntimeConfidenceCalibrationEngine.kt",
"OnlineParameterAdaptationEngine.kt",
"TacticalAnalyticsEngine.kt",
"TacticalBehaviorRecognitionEngine.kt",
"TacticalIntelligenceEngine.kt"
]

replacements={

"((tactical.confidence+state.confidence)/2f).coerceIn(0f,1f)":
"""(
(
tactical.confidence*0.45f+
state.confidence*0.20f+
state.fieldConfidence*0.15f+
(if(state.ballDetected)0.10f else 0f)+
(if(state.playerDetected)0.10f else 0f)
).coerceIn(0f,1f)
)""",

"confidence*0.9f":
"(confidence*(0.70f+state.fieldConfidence*0.30f)).coerceIn(0f,1f)",

"confidence*0.8f":
"(confidence*(0.60f+state.confidence*0.40f)).coerceIn(0f,1f)",

"confidence*0.7f":
"(confidence*(0.50f+state.fieldConfidence*0.50f)).coerceIn(0f,1f)",

"confidence*0.6f":
"(confidence*(0.40f+state.confidence*0.60f)).coerceIn(0f,1f)",

"val laneScore = if (graph.lanes.isEmpty()) {\n            0f\n        } else {\n            tactical.confidence\n        }":
"""val laneScore=
graph.lanes
.maxOfOrNull{
(it.score*it.confidence).coerceIn(0f,1f)
}?:0f""",

"val confidence=tactical.confidence":
"""val confidence=(
tactical.confidence*0.55f+
(if(shooting.lanes.isEmpty())0f else shooting.lanes.maxOf{it.confidence}*0.45f)
).coerceIn(0f,1f)""",

")/3f":
")/3f",

")/4f":
")/4f"

}

for name in targets:
    p=pkg/name
    s=p.read_text()
    old=s
    for a,b in replacements.items():
        s=s.replace(a,b)
    if s!=old:
        p.write_text(s)

PY

echo
echo "========== VERIFY =========="
grep -RIn \
-E 'history|rolling|window|ema|moving|momentum|maxOfOrNull|maxOf|fieldConfidence|ballDetected|playerDetected|goalDetected|goalkeeperDetected' \
"$PKG"/OpponentBehaviourLearningEngine.kt \
"$PKG"/PlayerTendencyLearningEngine.kt \
"$PKG"/PreferredPassingLaneLearningEngine.kt \
"$PKG"/ShootingHabitLearningEngine.kt \
"$PKG"/FormationAdaptationEngine.kt \
"$PKG"/RuntimeConfidenceCalibrationEngine.kt \
"$PKG"/OnlineParameterAdaptationEngine.kt \
"$PKG"/TacticalAnalyticsEngine.kt \
"$PKG"/TacticalBehaviorRecognitionEngine.kt \
"$PKG"/TacticalIntelligenceEngine.kt

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin

#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

pkg=Path.home()/ "projects/Splendor-Assist/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

targets=[
"OpponentBehaviourLearningEngine.kt",
"PlayerTendencyLearningEngine.kt",
"PreferredPassingLaneLearningEngine.kt",
"ShootingHabitLearningEngine.kt",
"FormationAdaptationEngine.kt",
"RuntimeConfidenceCalibrationEngine.kt",
"OnlineParameterAdaptationEngine.kt",
"TacticalIntelligenceEngine.kt"
]

for name in targets:

    p=pkg/name
    s=p.read_text()

    if "temporal:TemporalMemoryState" in s:
        continue

    s=re.sub(
        r'(\s*fun analyze\([^\)]*)\)',
        lambda m:m.group(1)+',\n        temporal:TemporalMemoryState\n    )',
        s,
        count=1,
        flags=re.S
    )

    replacements={

"OpponentBehaviourLearningEngine.kt":
"""
        val temporalConfidence =
            (
                temporal.exponentialMovingAverage*0.30f+
                temporal.rollingMean*0.20f+
                temporal.temporalConfidence*0.20f+
                (1f-temporal.confidenceVariance).coerceIn(0f,1f)*0.15f+
                (0.5f+temporal.confidenceTrend*0.5f).coerceIn(0f,1f)*0.15f
            ).coerceIn(0f,1f)

        val confidence=(
            tactical.confidence*0.35f+
            state.confidence*0.15f+
            state.fieldConfidence*0.10f+
            temporalConfidence*0.40f
        ).coerceIn(0f,1f)
""",

"PlayerTendencyLearningEngine.kt":
"""
        val temporalConfidence =
            (
                temporal.exponentialMovingAverage*0.30f+
                temporal.rollingMean*0.25f+
                temporal.temporalConfidence*0.20f+
                (0.5f+temporal.confidenceSlope*0.5f).coerceIn(0f,1f)*0.15f+
                (1f-temporal.confidenceVariance).coerceIn(0f,1f)*0.10f
            ).coerceIn(0f,1f)

        val confidence=(
            tactical.confidence*0.35f+
            state.confidence*0.15f+
            state.fieldConfidence*0.10f+
            temporalConfidence*0.40f
        ).coerceIn(0f,1f)
""",

"PreferredPassingLaneLearningEngine.kt":
"""
        val temporalScore =
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence
            )/3f

        val laneScore=
            (
                laneScore*0.70f+
                temporalScore*0.30f
            ).coerceIn(0f,1f)
""",

"ShootingHabitLearningEngine.kt":
"""
        val temporalConfidence=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence
            )/3f

        val confidence=(
            tactical.confidence*0.40f+
            temporalConfidence*0.35f+
            confidence*0.25f
        ).coerceIn(0f,1f)
""",

"FormationAdaptationEngine.kt":
"""
        val temporalInfluence=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence+
                (0.5f+temporal.confidenceTrend*0.5f).coerceIn(0f,1f)
            )/4f

        val confidence=(
            tactical.confidence*0.25f+
            opponent.confidence*0.20f+
            player.confidence*0.20f+
            temporalInfluence*0.35f
        ).coerceIn(0f,1f)
""",

"RuntimeConfidenceCalibrationEngine.kt":
"""
        val calibrated=(
            tactical.confidence*0.25f+
            formation.confidence*0.20f+
            passing.confidence*0.20f+
            shooting.confidence*0.15f+
            temporal.exponentialMovingAverage*0.10f+
            temporal.rollingMean*0.05f+
            temporal.temporalConfidence*0.05f
        ).coerceIn(0f,1f)
""",

"OnlineParameterAdaptationEngine.kt":
"""
        val gain=(
            calibration.calibratedConfidence*0.35f+
            state.confidence*0.15f+
            state.fieldConfidence*0.10f+
            temporal.exponentialMovingAverage*0.15f+
            temporal.rollingMean*0.10f+
            temporal.temporalConfidence*0.10f+
            (1f-temporal.confidenceVariance).coerceIn(0f,1f)*0.05f
        ).coerceIn(0f,1f)
""",

"TacticalIntelligenceEngine.kt":
"""
        score+=temporal.exponentialMovingAverage
        score+=temporal.rollingMean
        score+=temporal.temporalConfidence
        score+=(1f-temporal.confidenceVariance).coerceIn(0f,1f)
        score+=(0.5f+temporal.confidenceTrend*0.5f).coerceIn(0f,1f)
"""
    }

    block=replacements.get(name)
    if block:
        if name=="TacticalIntelligenceEngine.kt":
            s=s.replace("score+=if(state.goalkeeperDetected)0.05f else 0f",
                        "score+=if(state.goalkeeperDetected)0.05f else 0f\n"+block)
        elif "val confidence=(" in s:
            s=s.replace(re.search(r'val confidence=.*?coerceIn\(0f,1f\)',s,re.S).group(0),block.strip(),1)
        elif "val calibrated=(" in s:
            s=s.replace(re.search(r'val calibrated=.*?coerceIn\(0f,1f\)',s,re.S).group(0),block.strip(),1)
        elif "val gain=(" in s:
            s=s.replace(re.search(r'val gain=.*?coerceIn\(0f,1f\)',s,re.S).group(0),block.strip(),1)
        elif "val laneScore =" in s:
            s=s.replace("return PreferredPassingLaneLearningResult(",block.strip()+"\n\n        return PreferredPassingLaneLearningResult(",1)

    p.write_text(s)

vision=pkg/"VisionCore.kt"
vs=vision.read_text()

calls=[
("OpponentBehaviourLearningEngine.analyze(","opponentBehaviourLearningResult"),
("PlayerTendencyLearningEngine.analyze(","playerTendencyLearningResult"),
("PreferredPassingLaneLearningEngine.analyze(","preferredPassingLaneLearningResult"),
("ShootingHabitLearningEngine.analyze(","shootingHabitLearningResult"),
("FormationAdaptationEngine.analyze(","formationAdaptationResult"),
("RuntimeConfidenceCalibrationEngine.analyze(","runtimeConfidenceCalibrationResult"),
("OnlineParameterAdaptationEngine.analyze(","onlineParameterAdaptationResult"),
("TacticalIntelligenceEngine.analyze(","tacticalIntelligenceResult")
]

for fn,_ in calls:
    vs=vs.replace(")\n",",\n              temporalMemoryState\n          )\n",1) if False else vs

vs=vs.replace(
"""OpponentBehaviourLearningEngine.analyze(
              tacticalIntelligenceResult,
              state
          )""",
"""OpponentBehaviourLearningEngine.analyze(
              tacticalIntelligenceResult,
              state,
              temporalMemoryState
          )""")

vs=vs.replace(
"""PlayerTendencyLearningEngine.analyze(
              tacticalIntelligenceResult,
              state
          )""",
"""PlayerTendencyLearningEngine.analyze(
              tacticalIntelligenceResult,
              state,
              temporalMemoryState
          )""")

vs=vs.replace(
"""PreferredPassingLaneLearningEngine.analyze(
              passingGraph,
              tacticalIntelligenceResult
          )""",
"""PreferredPassingLaneLearningEngine.analyze(
              passingGraph,
              tacticalIntelligenceResult,
              temporalMemoryState
          )""")

vs=vs.replace(
"""ShootingHabitLearningEngine.analyze(
              shootingLaneAnalysis,
              tacticalIntelligenceResult
          )""",
"""ShootingHabitLearningEngine.analyze(
              shootingLaneAnalysis,
              tacticalIntelligenceResult,
              temporalMemoryState
          )""")

vs=vs.replace(
"""FormationAdaptationEngine.analyze(
              tacticalIntelligenceResult,
              opponentBehaviourLearningResult,
              playerTendencyLearningResult
          )""",
"""FormationAdaptationEngine.analyze(
              tacticalIntelligenceResult,
              opponentBehaviourLearningResult,
              playerTendencyLearningResult,
              temporalMemoryState
          )""")

vs=vs.replace(
"""RuntimeConfidenceCalibrationEngine.analyze(
              tacticalIntelligenceResult,
              formationAdaptationResult,
              preferredPassingLaneLearningResult,
              shootingHabitLearningResult
          )""",
"""RuntimeConfidenceCalibrationEngine.analyze(
              tacticalIntelligenceResult,
              formationAdaptationResult,
              preferredPassingLaneLearningResult,
              shootingHabitLearningResult,
              temporalMemoryState
          )""")

vs=vs.replace(
"""OnlineParameterAdaptationEngine.analyze(
              runtimeConfidenceCalibrationResult,
              state
          )""",
"""OnlineParameterAdaptationEngine.analyze(
              runtimeConfidenceCalibrationResult,
              state,
              temporalMemoryState
          )""")

vs=vs.replace(
"""TacticalIntelligenceEngine.analyze(
          tacticalAnalyticsResult,
          tacticalBehaviorRecognitionResult,
          state
      )""",
"""TacticalIntelligenceEngine.analyze(
          tacticalAnalyticsResult,
          tacticalBehaviorRecognitionResult,
          state,
          temporalMemoryState
      )""")

vision.write_text(vs)
PY

echo "========== VERIFY =========="

grep -RIn "temporal:TemporalMemoryState" "$PKG"/*Engine.kt

grep -RIn \
"rollingMean\|exponentialMovingAverage\|confidenceVariance\|confidenceTrend\|temporalConfidence" \
"$PKG"/*Engine.kt

grep -n "temporalMemoryState" \
"$PKG/VisionCore.kt"

echo
echo "========== BUILD =========="

cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin

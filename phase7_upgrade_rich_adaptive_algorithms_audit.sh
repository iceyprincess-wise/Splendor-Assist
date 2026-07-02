#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUT="/sdcard/SplendorAssist-Audits"
REPORT="$OUT/PHASE7_UPGRADE_RICH_ADAPTIVE_ALGORITHMS_AUDIT.txt"

mkdir -p "$OUT"

{
echo "======================================================================"
echo "PHASE 7 UPGRADE → RICH ADAPTIVE ALGORITHM IMPLEMENTATION AUDIT"
echo "======================================================================"
date
echo

cd "$ROOT"

echo "================ BUILD ================="
./gradlew :adapter_smartassist:compileDebugKotlin || true
echo

echo "================ TACTICAL ENGINES ================="
for f in \
TacticalAnalyticsEngine.kt \
TacticalBehaviorRecognitionEngine.kt \
TacticalIntelligenceEngine.kt \
OpponentBehaviourLearningEngine.kt \
PlayerTendencyLearningEngine.kt \
PreferredPassingLaneLearningEngine.kt \
ShootingHabitLearningEngine.kt \
FormationAdaptationEngine.kt \
RuntimeConfidenceCalibrationEngine.kt \
OnlineParameterAdaptationEngine.kt
do
echo
echo "############ $f ############"
nl -ba "$PKG/$f"
done

echo
echo "================ RESULT CLASSES ================="
for f in \
TacticalAnalyticsResult.kt \
TacticalBehaviorRecognitionResult.kt \
TacticalIntelligenceResult.kt \
OpponentBehaviourLearningResult.kt \
PlayerTendencyLearningResult.kt \
PreferredPassingLaneLearningResult.kt \
ShootingHabitLearningResult.kt \
FormationAdaptationResult.kt \
RuntimeConfidenceCalibrationResult.kt \
OnlineParameterAdaptationResult.kt
do
echo
echo "############ $f ############"
nl -ba "$PKG/$f"
done

echo
echo "================ VISIONCORE WIRING ================="
grep -nE \
'TacticalAnalyticsEngine|TacticalBehaviorRecognitionEngine|TacticalIntelligenceEngine|OpponentBehaviourLearningEngine|PlayerTendencyLearningEngine|PreferredPassingLaneLearningEngine|ShootingHabitLearningEngine|FormationAdaptationEngine|RuntimeConfidenceCalibrationEngine|OnlineParameterAdaptationEngine|Phase3WorldState\\(' \
"$PKG/VisionCore.kt" || true
echo
nl -ba "$PKG/VisionCore.kt" | sed -n '250,430p'

echo
echo "================ PHASE3WORLDSTATE ================="
nl -ba "$PKG/Phase3WorldState.kt"

echo
echo "================ ACTIVEGESTURECONTROLLER ================="
grep -nE \
'decisionDistance|runtimeConfidenceCalibrationResult|onlineParameterAdaptationResult|formationAdaptationResult|preferredPassingLaneLearningResult|playerTendencyLearningResult|opponentBehaviourLearningResult|shootingHabitLearningResult|tacticalIntelligenceResult' \
"$PKG/ActiveGestureController.kt" || true
echo
nl -ba "$PKG/ActiveGestureController.kt" | sed -n '110,320p'

echo
echo "================ CURRENT ADAPTIVE COMPLEXITY ================="
grep -RIn \
-E 'history|rolling|window|ema|movingAverage|decay|momentum|variance|std|bayes|kalman|adaptive|gradient|online|learningRate|forget|memory|temporal|frameHistory|previousFrame|sampleCount|confidenceHistory' \
"$PKG" || true

echo
echo "================ PLACEHOLDER HEURISTICS ================="
grep -RIn \
-E 'confidence\\*0\\.|confidence\\*0\\.[0-9]|/2f|/3f|/4f|/5f|coerceIn\\(0f,1f\\)|if \\(.*\\)0\\.0?5f|0\\.10f|0\\.20f|return .*Result' \
"$PKG"/Tactical*Engine.kt \
"$PKG"/OpponentBehaviourLearningEngine.kt \
"$PKG"/PlayerTendencyLearningEngine.kt \
"$PKG"/PreferredPassingLaneLearningEngine.kt \
"$PKG"/ShootingHabitLearningEngine.kt \
"$PKG"/FormationAdaptationEngine.kt \
"$PKG"/RuntimeConfidenceCalibrationEngine.kt \
"$PKG"/OnlineParameterAdaptationEngine.kt || true

echo
echo "================ END ================="

} > "$REPORT"

echo
echo "AUDIT COMPLETE"
echo "$REPORT"


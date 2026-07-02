#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUT="/sdcard/SplendorAssist-Audits"
REPORT="$OUT/PHASE8_TEMPORAL_ADAPTIVE_GAMEPLAY_AUDIT.txt"

mkdir -p "$OUT"

{
echo "======================================================================"
echo "PHASE 8 PREPARATION AUDIT"
echo "TEMPORAL LEARNING + ADAPTIVE AI + GAMEPLAY DECISION ENGINE"
echo "======================================================================"
date
echo

cd "$ROOT"

echo "================ BUILD ================="
./gradlew :adapter_smartassist:compileDebugKotlin || true
echo

echo "================ GAMEPLAY DECISION PIPELINE ================="
grep -RIn \
-E 'Decision|DecisionEngine|DecisionResult|DecisionScore|DecisionGraph|DecisionTree|Gameplay|GamePlay|ActionScore|ActionRanking|DecisionDistance|ActionSelection|GestureDecision|GestureRanking|BestAction|Policy' \
"$PKG" || true
echo

echo "================ ACTIVE GESTURE CONTROLLER ================="
nl -ba "$PKG/ActiveGestureController.kt"
echo

echo "================ VISION CORE ================="
nl -ba "$PKG/VisionCore.kt"
echo

echo "================ PHASE3 WORLD STATE ================="
nl -ba "$PKG/Phase3WorldState.kt"
echo

echo "================ ADAPTIVE ENGINES ================="
for f in \
OpponentBehaviourLearningEngine.kt \
PlayerTendencyLearningEngine.kt \
PreferredPassingLaneLearningEngine.kt \
ShootingHabitLearningEngine.kt \
FormationAdaptationEngine.kt \
RuntimeConfidenceCalibrationEngine.kt \
OnlineParameterAdaptationEngine.kt \
TacticalAnalyticsEngine.kt \
TacticalBehaviorRecognitionEngine.kt \
TacticalIntelligenceEngine.kt
do
echo
echo "############ $f ############"
nl -ba "$PKG/$f"
done

echo
echo "================ RESULT OBJECTS ================="
for f in \
OpponentBehaviourLearningResult.kt \
PlayerTendencyLearningResult.kt \
PreferredPassingLaneLearningResult.kt \
ShootingHabitLearningResult.kt \
FormationAdaptationResult.kt \
RuntimeConfidenceCalibrationResult.kt \
OnlineParameterAdaptationResult.kt \
TacticalAnalyticsResult.kt \
TacticalBehaviorRecognitionResult.kt \
TacticalIntelligenceResult.kt
do
echo
echo "############ $f ############"
nl -ba "$PKG/$f"
done

echo
echo "================ TEMPORAL LEARNING SEARCH ================="
grep -RIn \
-E 'history|frameHistory|rolling|window|ring|buffer|ema|movingAverage|moving average|weightedAverage|adaptive|learningRate|forgetFactor|decay|momentum|variance|std|online|bayes|kalman|memory|temporal|previousFrame|sampleCount|historySize|confidenceHistory|trend|prediction' \
"$PKG" || true
echo

echo "================ GAMEPLAY DECISION LOGIC SEARCH ================="
grep -RIn \
-E 'shoot|pass|cross|dribble|clear|press|counter|mark|switch|support|run|movement|formation|transition|attack|defend|intercept|offside|goal|goalkeeper|risk|reward|utility|score|ranking|priority|policy|heuristic' \
"$PKG" || true
echo

echo "================ STATE PROPAGATION ================="
grep -RIn \
-E 'OpponentBehaviourLearningResult|PlayerTendencyLearningResult|PreferredPassingLaneLearningResult|ShootingHabitLearningResult|FormationAdaptationResult|RuntimeConfidenceCalibrationResult|OnlineParameterAdaptationResult|TacticalAnalyticsResult|TacticalBehaviorRecognitionResult|TacticalIntelligenceResult' \
"$PKG" || true
echo

echo "================ PLACEHOLDER / STATIC HEURISTICS ================="
grep -RIn \
-E 'return .*Result|coerceIn\(0f,1f\)|0\.05f|0\.10f|0\.15f|0\.20f|/2f|/3f|/4f|maxOrNull|average|TODO|FIXME' \
"$PKG" || true
echo

echo "================ END OF AUDIT ================="

} > "$REPORT"

echo
echo "AUDIT COMPLETE"
echo "$REPORT"


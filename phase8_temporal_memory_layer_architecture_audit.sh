#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUT="/sdcard/SplendorAssist-Audits"
REPORT="$OUT/PHASE8_TEMPORAL_MEMORY_LAYER_ARCHITECTURE_AUDIT.txt"

mkdir -p "$OUT"

{
echo "======================================================================"
echo "PHASE 8 : TEMPORAL MEMORY LAYER ARCHITECTURE AUDIT"
echo "======================================================================"
date
echo

cd "$ROOT"

echo "================ BUILD ================="
./gradlew :adapter_smartassist:compileDebugKotlin || true
echo

echo "================ VISION CORE ================="
nl -ba "$PKG/VisionCore.kt"
echo

echo "================ PHASE3 WORLD STATE ================="
nl -ba "$PKG/Phase3WorldState.kt"
echo

echo "================ ACTIVE GESTURE CONTROLLER ================="
nl -ba "$PKG/ActiveGestureController.kt"
echo

echo "================ DECISION PIPELINE ================="
grep -RIn \
-E 'Decision|DecisionEngine|DecisionResult|Gameplay|Gesture|Policy|DecisionDistance|ActionScore|ActionSelection|DecisionScore' \
"$PKG" || true
echo

echo "================ TEMPORAL / MEMORY SEARCH ================="
grep -RIn \
-E 'history|History|frameHistory|rolling|window|ring|buffer|deque|queue|ema|EMA|movingAverage|weightedAverage|forget|decay|aging|trend|temporal|sampleCount|historySize|confidenceHistory|previousFrame|lastFrame|memory|adaptiveMemory|observationHistory|stateHistory|statistics' \
"$PKG" || true
echo

echo "================ LEARNING ENGINES ================="
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
echo "================ STATE PROPAGATION ================="
grep -RIn \
-E 'OpponentBehaviourLearningResult|PlayerTendencyLearningResult|PreferredPassingLaneLearningResult|ShootingHabitLearningResult|FormationAdaptationResult|RuntimeConfidenceCalibrationResult|OnlineParameterAdaptationResult|TacticalAnalyticsResult|TacticalBehaviorRecognitionResult|TacticalIntelligenceResult' \
"$PKG" || true
echo

echo "================ DATA STRUCTURES ================="
grep -RIn \
-E 'ArrayDeque|MutableList|LinkedList|ArrayList|Circular|Ring|Buffer|Deque|Queue|Map<|MutableMap|HashMap|TreeMap|LinkedHashMap|FloatArray|DoubleArray|IntArray' \
"$PKG" || true
echo

echo "================ PERSISTENCE ================="
grep -RIn \
-E 'SharedPreferences|DataStore|Room|database|persist|save|load|serialize|deserialize' \
"$PKG" || true
echo

echo "================ PLACEHOLDER / HEURISTIC SEARCH ================="
grep -RIn \
-E 'TODO|FIXME|coerceIn\(0f,1f\)|/2f|/3f|/4f|0\.05f|0\.10f|0\.15f|0\.20f|maxOrNull|average|return .*Result' \
"$PKG" || true
echo

echo "================ END OF AUDIT ================="

} > "$REPORT"

echo
echo "AUDIT COMPLETE"
echo "$REPORT"

#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUT="/sdcard/SplendorAssist-Audits"
REPORT="$OUT/PHASE7_RICH_ADAPTIVE_ALGORITHMS_AUDIT.txt"

mkdir -p "$OUT"

{
echo "=============================================================="
echo "PHASE 7 RICH ADAPTIVE ALGORITHMS ARCHITECTURE AUDIT"
echo "=============================================================="
echo
date
echo
echo "PROJECT ROOT"
echo "$ROOT"
echo

echo "=============================================================="
echo "BUILD VERIFICATION"
echo "=============================================================="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin || true
echo

echo "=============================================================="
echo "ADAPTIVE ENGINES"
echo "=============================================================="
find "$PKG" -maxdepth 1 | sort | grep -E \
'OpponentBehaviourLearningEngine|OpponentBehaviourLearningResult|PlayerTendencyLearningEngine|PlayerTendencyLearningResult|PreferredPassingLaneLearningEngine|PreferredPassingLaneLearningResult|ShootingHabitLearningEngine|ShootingHabitLearningResult|FormationAdaptationEngine|FormationAdaptationResult|RuntimeConfidenceCalibrationEngine|RuntimeConfidenceCalibrationResult|OnlineParameterAdaptationEngine|OnlineParameterAdaptationResult|TacticalAnalyticsEngine|TacticalBehaviorRecognitionEngine|TacticalIntelligenceEngine'
echo

echo "=============================================================="
echo "ENGINE IMPLEMENTATIONS"
echo "=============================================================="
grep -RIn --color=never \
-E 'object |fun analyze|confidence|score|learning|adapt|history|memory|EMA|movingAverage|update|return' \
"$PKG"/OpponentBehaviourLearningEngine.kt \
"$PKG"/PlayerTendencyLearningEngine.kt \
"$PKG"/PreferredPassingLaneLearningEngine.kt \
"$PKG"/ShootingHabitLearningEngine.kt \
"$PKG"/FormationAdaptationEngine.kt \
"$PKG"/RuntimeConfidenceCalibrationEngine.kt \
"$PKG"/OnlineParameterAdaptationEngine.kt \
"$PKG"/TacticalAnalyticsEngine.kt \
"$PKG"/TacticalBehaviorRecognitionEngine.kt \
"$PKG"/TacticalIntelligenceEngine.kt 2>/dev/null || true
echo

echo "=============================================================="
echo "RESULT DATA STRUCTURES"
echo "=============================================================="
grep -RIn --color=never \
-E 'data class|val |Float|Boolean|Int|List<' \
"$PKG"/*Result.kt 2>/dev/null || true
echo

echo "=============================================================="
echo "VISIONCORE PIPELINE"
echo "=============================================================="
grep -nE \
'OpponentBehaviourLearningEngine|PlayerTendencyLearningEngine|PreferredPassingLaneLearningEngine|ShootingHabitLearningEngine|FormationAdaptationEngine|RuntimeConfidenceCalibrationEngine|OnlineParameterAdaptationEngine|TacticalAnalyticsEngine|TacticalBehaviorRecognitionEngine|TacticalIntelligenceEngine' \
"$PKG/VisionCore.kt" || true
echo

echo "=============================================================="
echo "PHASE3WORLDSTATE"
echo "=============================================================="
grep -nE \
'OpponentBehaviourLearningResult|PlayerTendencyLearningResult|PreferredPassingLaneLearningResult|ShootingHabitLearningResult|FormationAdaptationResult|RuntimeConfidenceCalibrationResult|OnlineParameterAdaptationResult|TacticalAnalyticsResult|TacticalBehaviorRecognitionResult|TacticalIntelligenceResult' \
"$PKG/Phase3WorldState.kt" || true
echo

echo "=============================================================="
echo "ACTIVEGESTURECONTROLLER"
echo "=============================================================="
grep -nE \
'decisionDistance|adaptiveConfidence|onlineParameterAdaptationResult|runtimeConfidenceCalibrationResult|formationAdaptationResult|preferredPassingLaneLearningResult|playerTendencyLearningResult|opponentBehaviourLearningResult|tacticalIntelligenceResult' \
"$PKG/ActiveGestureController.kt" || true
echo

echo "=============================================================="
echo "TEMPORAL / LEARNING STATE SEARCH"
echo "=============================================================="
grep -RIn --color=never \
-E 'history|rolling|window|ema|moving|average|trend|adapt|learn|memory|cache|previous|lastFrame|frameHistory|observation|sample|confidenceHistory|decay' \
"$PKG" 2>/dev/null || true
echo

echo "=============================================================="
echo "PERSISTENCE SEARCH"
echo "=============================================================="
grep -RIn --color=never \
-E 'SharedPreferences|DataStore|Room|database|persist|save|load|serialize|deserialize' \
"$PKG" 2>/dev/null || true
echo

echo "=============================================================="
echo "UNUSED / PLACEHOLDER SEARCH"
echo "=============================================================="
grep -RIn --color=never \
-E 'TODO|FIXME|return .*Result\(\)|return .*0f|confidence=0f|adaptationScore=0f|learningScore=0f' \
"$PKG" 2>/dev/null || true
echo

echo "=============================================================="
echo "END OF AUDIT"
echo "=============================================================="

} > "$REPORT"

echo
echo "AUDIT COMPLETE"
echo "$REPORT"

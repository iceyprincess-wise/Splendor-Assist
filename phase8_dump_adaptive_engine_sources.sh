#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUT="/sdcard/SplendorAssist-Audits"
REPORT="$OUT/PHASE8_ADAPTIVE_ENGINE_SOURCE_DUMP.txt"

mkdir -p "$OUT"

{
echo "======================================================================"
echo "PHASE 8 COMPLETE ADAPTIVE ENGINE SOURCE DUMP"
echo "======================================================================"
date
echo

cd "$ROOT"

echo "================ BUILD ================="
./gradlew :adapter_smartassist:compileDebugKotlin || true
echo

FILES="
VisionCore.kt
Phase3WorldState.kt
Phase3WorldStateStore.kt
ActiveGestureController.kt
GameplayDecisionEngine.kt
TemporalMemoryEngine.kt
TemporalMemoryState.kt
OpponentBehaviourLearningEngine.kt
OpponentBehaviourLearningResult.kt
PlayerTendencyLearningEngine.kt
PlayerTendencyLearningResult.kt
PreferredPassingLaneLearningEngine.kt
PreferredPassingLaneLearningResult.kt
ShootingHabitLearningEngine.kt
ShootingHabitLearningResult.kt
FormationAdaptationEngine.kt
FormationAdaptationResult.kt
RuntimeConfidenceCalibrationEngine.kt
RuntimeConfidenceCalibrationResult.kt
OnlineParameterAdaptationEngine.kt
OnlineParameterAdaptationResult.kt
TacticalAnalyticsEngine.kt
TacticalAnalyticsResult.kt
TacticalBehaviorRecognitionEngine.kt
TacticalBehaviorRecognitionResult.kt
TacticalIntelligenceEngine.kt
TacticalIntelligenceResult.kt
"

for F in $FILES
do
    if [ -f "$PKG/$F" ]; then
        echo
        echo "======================================================================"
        echo "FILE: $F"
        echo "======================================================================"
        nl -ba "$PKG/$F"
    else
        echo
        echo "======================================================================"
        echo "MISSING: $F"
        echo "======================================================================"
    fi
done

echo
echo "================ ENGINE REFERENCES ================="
grep -RIn \
-E 'TemporalMemory|OpponentBehaviourLearning|PlayerTendencyLearning|PreferredPassingLaneLearning|ShootingHabitLearning|FormationAdaptation|RuntimeConfidenceCalibration|OnlineParameterAdaptation|TacticalAnalytics|TacticalBehaviorRecognition|TacticalIntelligence|GameplayDecisionEngine' \
"$PKG" || true

echo
echo "================ END OF DUMP ================="

} > "$REPORT"

echo
echo "SOURCE DUMP COMPLETE"
echo "$REPORT"


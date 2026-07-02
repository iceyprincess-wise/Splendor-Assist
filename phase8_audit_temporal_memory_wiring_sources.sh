#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUT="/sdcard/SplendorAssist-Audits"
REPORT="$OUT/PHASE8_TEMPORAL_MEMORY_WIRING_SOURCE_AUDIT.txt"

mkdir -p "$OUT"

{
echo "======================================================================"
echo "PHASE 8 TEMPORAL MEMORY WIRING SOURCE AUDIT"
echo "======================================================================"
date
echo

cd "$ROOT"

echo "================ BUILD ================="
./gradlew :adapter_smartassist:compileDebugKotlin || true
echo

echo "================ VisionCore.kt ================="
nl -ba "$PKG/VisionCore.kt"
echo

echo "================ Phase3WorldState.kt ================="
nl -ba "$PKG/Phase3WorldState.kt"
echo

echo "================ ActiveGestureController.kt ================="
nl -ba "$PKG/ActiveGestureController.kt"
echo

echo "================ TemporalMemoryEngine.kt ================="
nl -ba "$PKG/TemporalMemoryEngine.kt"
echo

echo "================ TemporalMemoryState.kt ================="
nl -ba "$PKG/TemporalMemoryState.kt"
echo

echo "================ WORLD STATE CONSTRUCTION ================="
grep -RIn \
-E 'Phase3WorldState\(' \
"$PKG" || true
echo

echo "================ WORLD STATE USAGE ================="
grep -RIn \
-E 'worldState\.|Phase3WorldState' \
"$PKG" || true
echo

echo "================ TEMPORAL MEMORY SEARCH ================="
grep -RIn \
-E 'TemporalMemoryEngine|TemporalMemoryState' \
"$PKG" || true
echo

echo "================ ADAPTIVE PIPELINE ================="
grep -RIn \
-E 'OpponentBehaviourLearningResult|PlayerTendencyLearningResult|PreferredPassingLaneLearningResult|ShootingHabitLearningResult|FormationAdaptationResult|RuntimeConfidenceCalibrationResult|OnlineParameterAdaptationResult|TacticalAnalyticsResult|TacticalBehaviorRecognitionResult|TacticalIntelligenceResult' \
"$PKG" || true
echo

echo "================ END AUDIT ================="

} > "$REPORT"

echo
echo "AUDIT COMPLETE"
echo "$REPORT"

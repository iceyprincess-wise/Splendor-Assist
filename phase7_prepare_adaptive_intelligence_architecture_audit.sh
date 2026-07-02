#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUT="$OUTDIR/PHASE7_ADAPTIVE_INTELLIGENCE_ARCHITECTURE_AUDIT.txt"

mkdir -p "$OUTDIR"

{
echo "======================================================================"
echo "PHASE 7 ADAPTIVE INTELLIGENCE ARCHITECTURE AUDIT"
echo "======================================================================"
date
echo

echo "================ COMPLETE SOURCE TREE ================="
find "$PKG" -name "*.kt" | sort
echo

echo "================ ADAPTIVE / LEARNING SEARCH ================="
grep -RIn --include="*.kt" \
-E "Opponent|Behaviour|Behavior|Learning|Learner|Adaptive|Adaptation|Online|Calibration|Confidence|Runtime|Parameter|Trend|Tendency|Habit|History|Memory|Statistics|PassingLane|Shot|FormationAdapt|Profile|Model|Experience|Knowledge" \
"$PKG" || true
echo

echo "================ VISIONCORE ================="
nl -ba "$PKG/VisionCore.kt"
echo

echo "================ PHASE3WORLDSTATE ================="
nl -ba "$PKG/Phase3WorldState.kt"
echo

echo "================ ACTIVE GESTURE CONTROLLER ================="
[ -f "$PKG/ActiveGestureController.kt" ] && nl -ba "$PKG/ActiveGestureController.kt"
echo

echo "================ GAME STATE ================="
[ -f "$PKG/GameStateSnapshot.kt" ] && nl -ba "$PKG/GameStateSnapshot.kt"
echo

echo "================ SCENE SNAPSHOT ================="
[ -f "$PKG/SceneSnapshot.kt" ] && nl -ba "$PKG/SceneSnapshot.kt"
echo

echo "================ TACTICAL PIPELINE ================="
grep -RIn --include="*.kt" \
-E "TacticalAnalytics|TacticalBehavior|TacticalIntelligence|PressingRecognition|CounterPressRecognition|BuildUpRecognition|PossessionStyleRecognition|Formation|TeamShape|PressureField|SpaceOccupancy" \
"$PKG" || true
echo

echo "================ EXISTING HISTORY / CACHE ================="
grep -RIn --include="*.kt" \
-E "MutableList|ArrayDeque|RingBuffer|History|Cache|Window|Rolling|Average|EMA|Moving|Temporal|FrameHistory|SnapshotHistory" \
"$PKG" || true
echo

echo "================ TARGET PHASE 7 FILES ================="
find "$PKG" \
\( \
-name "OpponentBehaviorLearningEngine.kt" -o \
-name "OpponentBehaviorLearningResult.kt" -o \
-name "PlayerTendencyLearningEngine.kt" -o \
-name "PlayerTendencyLearningResult.kt" -o \
-name "PreferredPassingLaneLearningEngine.kt" -o \
-name "PreferredPassingLaneLearningResult.kt" -o \
-name "ShootingHabitLearningEngine.kt" -o \
-name "ShootingHabitLearningResult.kt" -o \
-name "FormationAdaptationEngine.kt" -o \
-name "FormationAdaptationResult.kt" -o \
-name "RuntimeConfidenceCalibrationEngine.kt" -o \
-name "RuntimeConfidenceCalibrationResult.kt" -o \
-name "OnlineParameterAdaptationEngine.kt" -o \
-name "OnlineParameterAdaptationResult.kt" \
\)

echo
echo "================ BUILD VERIFY ================="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin --quiet || true

echo
echo "======================================================================"
echo "AUDIT COMPLETE"
echo "OUTPUT:"
echo "$OUT"
echo "======================================================================"

} > "$OUT"

echo
echo "AUDIT COMPLETE"
echo "$OUT"

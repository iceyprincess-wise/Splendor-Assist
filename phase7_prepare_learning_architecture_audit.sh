#!/data/data/com.termux/files/usr/bin/bash
set -eu

ROOT="$HOME/projects/Splendor-Assist"
OUT="/sdcard/SplendorAssist-Audits"
REPORT="$OUT/PHASE7_LEARNING_ARCHITECTURE_AUDIT.txt"

mkdir -p "$OUT"

cd "$ROOT"

exec >"$REPORT" 2>&1

echo "======================================================================"
echo "PHASE 7 LEARNING ARCHITECTURE AUDIT"
echo "======================================================================"
date
echo

echo "================ PROJECT ROOT ================="
pwd
echo

echo "================ BUILD ================="
./gradlew :adapter_smartassist:compileDebugKotlin
echo

echo "================ LEARNING FILE SEARCH ================="
find adapter_smartassist/src/main/java -type f \( -name "*.kt" -o -name "*.java" \) \
| sort
echo

echo "================ OPPONENT / PLAYER / LEARNING SEARCH ================="
grep -RInE \
'Opponent|Player|Learning|Learn|Behaviour|Behavior|Tendency|Habit|PassingLane|Passing|Shot|Shooting|Formation|Adaptive|Adapt|Confidence|Runtime|Online|History|Memory|Profile|Analytics|Intelligence' \
adapter_smartassist/src/main/java || true
echo

echo "================ VISIONCORE ================="
grep -n -A8 -B4 \
'E\.analyze|E\.compute|Opponent|Player|Learning|Passing|Shot|Formation|Analytics|Behavior|Intelligence' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt || true
echo

echo "================ PHASE3WORLDSTATE ================="
cat adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldState.kt
echo

echo "================ RESULT CLASSES ================="
find adapter_smartassist/src/main/java \
-type f \
-name "*Result.kt" \
-print | sort
echo

echo "================ ENGINE CLASSES ================="
find adapter_smartassist/src/main/java \
-type f \
-name "*Engine.kt" \
-print | sort
echo

echo "================ PASSING GRAPH ================="
grep -RIn \
'PassingLaneGraph|PassingLane|ReceiverRanking|ShotOpportunity|PassingGraph' \
adapter_smartassist/src/main/java || true
echo

echo "================ FORMATION ================="
grep -RIn \
'FormationResult|FormationEngine|TeamShapeResult|TeamShapeEngine' \
adapter_smartassist/src/main/java || true
echo

echo "================ GAME STATE ================="
grep -RIn \
'data class GameStateSnapshot|class GameStateSnapshot|SceneSnapshot|TelemetrySnapshot' \
adapter_smartassist/src/main/java || true
echo

echo "================ END ================="
echo "AUDIT COMPLETE"
echo "$REPORT"

#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUT="/sdcard/SplendorAssist-Audits/PHASE7_PHASE3WORLDSTATE_STRUCTURE.txt"

mkdir -p "$(dirname "$OUT")"

{
echo "================ PHASE3WORLDSTATE LOCATION ================"
find "$PKG" -name "Phase3WorldState.kt" -print

echo
echo "================ FILE CONTENT ================"
nl -ba "$PKG/Phase3WorldState.kt"

echo
echo "================ DECLARATIONS ================"
grep -nE "data class|class|object|constructor|Phase3WorldState" "$PKG/Phase3WorldState.kt" || true

echo
echo "================ VISIONCORE LEARNING BLOCK ================"
grep -nA40 -B20 \
"E.analyze|OpponentBehaviourLearningEngine|PlayerTendencyLearningEngine|PreferredPassingLaneLearningEngine|ShootingHabitLearningEngine|Phase3WorldState" \
"$PKG/VisionCore.kt" || true

echo
echo "================ BUILD ================"
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin || true
} > "$OUT"

echo
echo "AUDIT COMPLETE"
echo "$OUT"

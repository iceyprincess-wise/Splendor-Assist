#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUT="$HOME/storage/shared/SplendorAssist-Audits"

mkdir -p "$OUT"

{
echo "================ GameplayDecisionEngine.kt ================"
nl -ba "$PKG/GameplayDecisionEngine.kt"

echo
echo "================ ActiveGestureController.kt ================"
nl -ba "$PKG/ActiveGestureController.kt"

echo
echo "================ TemporalMemoryState.kt ================"
nl -ba "$PKG/TemporalMemoryState.kt"

echo
echo "================ VisionCore.kt (260-390) ================"
awk 'NR>=260 && NR<=390 {printf "%6d  %s\n",NR,$0}' \
"$PKG/VisionCore.kt"

echo
echo "================ BUILD ================="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin --stacktrace
} > "$OUT/PHASE8_GAMEPLAY_TEMPORAL_AUDIT.txt" 2>&1

echo
echo "AUDIT COMPLETE"
echo "$OUT/PHASE8_GAMEPLAY_TEMPORAL_AUDIT.txt"

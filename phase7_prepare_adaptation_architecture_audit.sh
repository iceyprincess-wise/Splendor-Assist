#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUT="$OUTDIR/PHASE7_ADAPTATION_ARCHITECTURE_AUDIT.txt"

mkdir -p "$OUTDIR"

{
echo "======================================================================"
echo "PHASE 7 ADAPTATION ARCHITECTURE AUDIT"
echo "======================================================================"
date
echo

echo "================ BUILD ================="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin
echo

echo "================ COMPLETE SOURCE TREE ================="
find "$PKG" -name "*.kt" | sort
echo

echo "================ TARGET SEARCH ================="
grep -RIn --include="*.kt" \
-E "FormationAdapt|RuntimeConfidence|ConfidenceCalibration|OnlineParameter|ParameterAdapt|Adaptive|Calibration|Adaptation|Formation|confidence|parameter|learning|history|memory|rolling|ema|window" \
"$PKG" || true
echo

echo "================ VISIONCORE ================="
nl -ba "$PKG/VisionCore.kt"
echo

echo "================ PHASE3WORLDSTATE ================="
nl -ba "$PKG/Phase3WorldState.kt"
echo

echo "================ TACTICAL + LEARNING ENGINES ================="
grep -RIn --include="*.kt" \
-E "TacticalIntelligenceEngine|OpponentBehaviourLearningEngine|PlayerTendencyLearningEngine|PreferredPassingLaneLearningEngine|ShootingHabitLearningEngine" \
"$PKG" || true
echo

echo "================ GAME STATE ================="
[ -f "$PKG/GameStateSnapshot.kt" ] && nl -ba "$PKG/GameStateSnapshot.kt"
echo

echo "================ RESULT TYPES ================="
find "$PKG" -maxdepth 1 -name "*Result.kt" | sort
echo

echo "================ ENGINE TYPES ================="
find "$PKG" -maxdepth 1 -name "*Engine.kt" | sort
echo

echo "================ BUILD COMPLETE ================="
} > "$OUT" 2>&1

echo
echo "AUDIT COMPLETE"
echo "$OUT"

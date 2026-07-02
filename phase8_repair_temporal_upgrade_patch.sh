#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

OUT="$HOME/storage/shared/phase8_temporal_repair_dump.txt"

{
echo "================ PHASE8 TEMPORAL REPAIR DUMP ================"

for f in \
FormationAdaptationEngine.kt \
OpponentBehaviourLearningEngine.kt \
PlayerTendencyLearningEngine.kt \
PreferredPassingLaneLearningEngine.kt \
ShootingHabitLearningEngine.kt \
RuntimeConfidenceCalibrationEngine.kt \
OnlineParameterAdaptationEngine.kt \
TacticalIntelligenceEngine.kt \
VisionCore.kt
do
echo
echo "================ $f ================"
nl -ba "$PKG/$f"
done

echo
echo "================ BUILD ================"
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin --stacktrace
} > "$OUT" 2>&1

echo
echo "Dump created:"
echo "$OUT"

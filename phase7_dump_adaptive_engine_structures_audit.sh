#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

OUTDIR="/sdcard/SplendorAssist-Audits"
OUT="$OUTDIR/PHASE7_ADAPTIVE_ENGINE_STRUCTURES_AUDIT.txt"

mkdir -p "$OUTDIR"

exec >"$OUT" 2>&1

echo "======================================================================"
echo "PHASE 7 ADAPTIVE ENGINE STRUCTURE AUDIT"
echo "======================================================================"
date
echo

FILES="
TacticalAnalyticsEngine.kt
TacticalAnalyticsResult.kt
TacticalBehaviorRecognitionEngine.kt
TacticalBehaviorRecognitionResult.kt
TacticalIntelligenceEngine.kt
TacticalIntelligenceResult.kt
TacticalMapResult.kt
DefensiveCompactnessResult.kt
WingOverloadDetectionResult.kt
CentralOverloadDetectionResult.kt
PressingRecognitionResult.kt
CounterPressRecognitionResult.kt
BuildUpRecognitionResult.kt
PossessionStyleRecognitionResult.kt
FormationResult.kt
TeamShapeResult.kt
GameStateSnapshot.kt
DecisionResult.kt
SceneSnapshot.kt
VisionCore.kt
Phase3WorldState.kt
"

echo "================ FILE PRESENCE ================"
for f in $FILES
do
    printf "%-45s : " "$f"
    if [ -f "$PKG/$f" ]; then
        echo FOUND
    else
        echo MISSING
    fi
done
echo

for f in $FILES
do
    if [ -f "$PKG/$f" ]; then
        echo
        echo "=================================================================="
        echo "$f"
        echo "=================================================================="
        nl -ba "$PKG/$f"
    fi
done

echo
echo "================ DATA CLASS DEFINITIONS ================"
grep -RIn --include="*.kt" "^data class " "$PKG" || true
echo

echo "================ CLASS DEFINITIONS ================"
grep -RIn --include="*.kt" "^class " "$PKG" || true
echo

echo "================ OBJECT DEFINITIONS ================"
grep -RIn --include="*.kt" "^object " "$PKG" || true
echo

echo "================ FUNCTION SIGNATURES ================"
grep -RIn --include="*.kt" "fun analyze\|fun compute\|fun detect\|fun recognize" "$PKG" || true
echo

echo "================ RESULT PROPERTY REFERENCES ================"
grep -RIn --include="*.kt" \
-E "confidence|score|strength|probability|density|compact|overload|press|counter|build|possession|formation|shape|decision|analytics|behavior|intelligence" \
"$PKG" || true
echo

echo "================ BUILD VERIFY ================"
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin || true

echo
echo "======================================================================"
echo "AUDIT COMPLETE"
echo "======================================================================"


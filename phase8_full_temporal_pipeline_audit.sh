#!/data/data/com.termux/files/usr/bin/bash
set -e

cd "$HOME/projects/Splendor-Assist"

OUTDIR="/sdcard/SplendorAssist-Audits"
mkdir -p "$OUTDIR"

OUTFILE="$OUTDIR/PHASE8_FULL_TEMPORAL_PIPELINE_AUDIT.txt"

FILES=(
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryState.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldState.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldStateStore.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TacticalIntelligenceEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/OpponentBehaviourLearningEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PlayerTendencyLearningEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PreferredPassingLaneLearningEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ShootingHabitLearningEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/FormationAdaptationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeConfidenceCalibrationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/OnlineParameterAdaptationEngine.kt
)

{
echo "==================================================================="
echo "      PHASE 8 FULL TEMPORAL / ADAPTIVE PIPELINE AUDIT"
echo "==================================================================="
echo
date
echo

for F in "${FILES[@]}"; do
    echo
    echo "###################################################################"
    echo "# FILE: $F"
    echo "###################################################################"
    echo

    if [ ! -f "$F" ]; then
        echo "FILE NOT FOUND"
        continue
    fi

    echo "---------------- IMPORTS ----------------"
    grep -nE '^import ' "$F" || true
    echo

    echo "------------- DECLARATIONS --------------"
    grep -nE '^(data class|class|object|interface|enum class)' "$F" || true
    echo

    echo "-------------- FUNCTIONS ----------------"
    grep -nE '^[[:space:]]*(override[[:space:]]+)?fun[[:space:]]' "$F" || true
    echo

    echo "----------- TEMPORAL REFERENCES ---------"
    grep -nE 'TemporalMemoryState|TemporalMemoryEngine|temporalMemoryState|history|rolling|window|EMA|ema|exponentialMovingAverage|rollingMean|variance|confidenceTrend|confidenceVariance|temporalConfidence|historyStability|confidenceEvolution|trend|decay|aging|online|adaptive|adaptation' "$F" || true
    echo

    echo "----------- GAMEPLAY REFERENCES ---------"
    grep -nE 'GameplayDecisionEngine|ActiveGestureController|Phase3WorldState|Phase3WorldStateStore|VisionCore|decision|gesture|formation|tactical|learning|confidence|TemporalMemoryEngine\.update' "$F" || true
    echo

    echo "------------- TODO / FIXME --------------"
    grep -nE 'TODO|FIXME|BUG|XXX' "$F" || true
    echo

    echo "--------------- FULL SOURCE -------------"
    nl -ba "$F"
    echo
done

echo
echo "================ END OF AUDIT ================"
} > "$OUTFILE"

echo
echo "AUDIT COMPLETE"
echo "$OUTFILE"

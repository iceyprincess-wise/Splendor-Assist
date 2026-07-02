#!/data/data/com.termux/files/usr/bin/bash
set -e

cd "$HOME/projects/Splendor-Assist"

OUTDIR="/sdcard/SplendorAssist-Audits"
mkdir -p "$OUTDIR"

OUT="$OUTDIR/PHASE8_ALL7_CLOSED_LOOP_TEMPORAL_AUDIT.txt"

FILES="
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryState.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/Phase3WorldState.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TacticalIntelligenceEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/OpponentBehaviourLearningEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PlayerTendencyLearningEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/PreferredPassingLaneLearningEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ShootingHabitLearningEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/FormationAdaptationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeConfidenceCalibrationEngine.kt
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/OnlineParameterAdaptationEngine.kt
"

exec > "$OUT"

echo "======================================================================="
echo "        PHASE 8 : COMPLETE CLOSED LOOP TEMPORAL SYSTEM AUDIT"
echo "======================================================================="
echo
date
echo

echo "======================================================================="
echo "OBJECTIVES"
echo "======================================================================="
echo "1. Replace heuristic weighting with EMA / decay / confidence evolution."
echo "2. Temporal sequence modelling."
echo "3. History-aware gameplay."
echo "4. Continuous formation optimization."
echo "5. Adaptive confidence feedback."
echo "6. Rolling replay statistics."
echo "7. Closed-loop adaptive pipeline."
echo

for f in $FILES
do
echo
echo "#######################################################################"
echo "FILE : $f"
echo "#######################################################################"
echo

if [ ! -f "$f" ]; then
echo "MISSING FILE"
continue
fi

echo "=============================="
echo "DECLARATIONS"
echo "=============================="
grep -nE '^(data class|class|object|interface|enum class)' "$f" || true
echo

echo "=============================="
echo "FUNCTIONS"
echo "=============================="
grep -nE '^[[:space:]]*(override[[:space:]]+)?fun[[:space:]]' "$f" || true
echo

echo "=============================="
echo "TEMPORAL MEMORY"
echo "=============================="
grep -nE 'TemporalMemoryState|TemporalMemoryEngine|temporalMemoryState|temporalConfidence|rollingConfidence|rollingMean|rollingStdDev|historyWindow|history|historyStability|confidenceTrend|confidenceVariance|confidenceSlope|confidenceEvolution|onlineUpdateCount|sampleCount|decayFactor|observationAge|EMA|ema|exponentialMovingAverage' "$f" || true
echo

echo "=============================="
echo "ONLINE ADAPTATION"
echo "=============================="
grep -nE 'RuntimeConfidenceCalibration|OnlineParameterAdaptation|adaptation|adaptive|feedback|learning|learn|update|calibratedConfidence|adaptationGain|confidenceFeedback|online|closedLoop|loop' "$f" || true
echo

echo "=============================="
echo "GAMEPLAY DECISION"
echo "=============================="
grep -nE 'GameplayDecisionEngine|DecisionResult|decision|priority|mode|streak|trend|prediction|history|stable|stability|confidence|switch|prediction' "$f" || true
echo

echo "=============================="
echo "FORMATION"
echo "=============================="
grep -nE 'Formation|formation|optimizer|optimization|continuous|adaptation|threshold|gradient|score' "$f" || true
echo

echo "=============================="
echo "PLAYER / OPPONENT HISTORY"
echo "=============================="
grep -nE 'Opponent|Player|Behaviour|Behavior|Habit|Tendency|PassingLane|sequence|history|window|rolling|trend|prediction|memory' "$f" || true
echo

echo "=============================="
echo "CALL GRAPH"
echo "=============================="
grep -nE '\.analyze\(|\.update\(|\.compute\(|\.decide\(|Phase3WorldStateStore|TemporalMemoryEngine|GameplayDecisionEngine|RuntimeConfidenceCalibrationEngine|FormationAdaptationEngine|OnlineParameterAdaptationEngine|OpponentBehaviourLearningEngine|PlayerTendencyLearningEngine|PreferredPassingLaneLearningEngine|ShootingHabitLearningEngine' "$f" || true
echo

echo "=============================="
echo "TODO"
echo "=============================="
grep -nE 'TODO|FIXME|BUG|XXX|TEMP' "$f" || true
echo

echo "=============================="
echo "FULL SOURCE"
echo "=============================="
nl -ba "$f"
echo
done

echo
echo "======================================================================="
echo "GLOBAL CROSS REFERENCES"
echo "======================================================================="
echo

grep -RInE 'TemporalMemoryEngine\.update' adapter_smartassist/src/main/java || true
echo

grep -RInE 'GameplayDecisionEngine\.decide' adapter_smartassist/src/main/java || true
echo

grep -RInE 'RuntimeConfidenceCalibrationEngine' adapter_smartassist/src/main/java || true
echo

grep -RInE 'OnlineParameterAdaptationEngine' adapter_smartassist/src/main/java || true
echo

grep -RInE 'FormationAdaptationEngine' adapter_smartassist/src/main/java || true
echo

grep -RInE 'OpponentBehaviourLearningEngine' adapter_smartassist/src/main/java || true
echo

grep -RInE 'PlayerTendencyLearningEngine' adapter_smartassist/src/main/java || true
echo

grep -RInE 'PreferredPassingLaneLearningEngine' adapter_smartassist/src/main/java || true
echo

grep -RInE 'ShootingHabitLearningEngine' adapter_smartassist/src/main/java || true
echo

grep -RInE 'historyStability|confidenceEvolution|rollingMean|rollingStdDev|confidenceTrend|confidenceVariance|temporalConfidence|exponentialMovingAverage|decayFactor' adapter_smartassist/src/main/java || true
echo

echo "======================================================================="
echo "AUDIT COMPLETE"
echo "======================================================================="

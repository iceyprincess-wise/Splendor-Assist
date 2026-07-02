#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUTFILE="$OUTDIR/PHASE9_EXISTING_ENGINE_WIRING_AUDIT.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

{
echo "================ PHASE9 EXISTING ENGINE WIRING AUDIT ================"
echo "DATE: $(date)"
echo

echo "OBJECTIVE"
echo "Locate the REAL existing runtime performance engines and determine"
echo "their packages, declarations and callable APIs before integration."
echo

echo "================ FILE LOCATION ================="
find . -type f | grep -Ei \
'MemoryStabilityOptimizer|FrameDropStabilizer|VsyncInputAnchor|LatencyDefeatingInputEngine|HybridResponseCompensationEngine|SpeedCompensationEngine|MotionTracker|SceneTracker|BallTrajectoryPredictor|GoalkeeperTrajectoryPredictor|TelemetryCoordinator|TelemetryRepository|LiveVectorResolver|TemporalMemoryEngine|OnlineParameterAdaptationEngine'
echo

echo
echo "================ DECLARATIONS ================="
grep -RInE \
'^(class|object|interface|data class|enum class|sealed class)' \
. | grep -E \
'MemoryStabilityOptimizer|FrameDropStabilizer|VsyncInputAnchor|LatencyDefeatingInputEngine|HybridResponseCompensationEngine|SpeedCompensationEngine|MotionTracker|SceneTracker|BallTrajectoryPredictor|GoalkeeperTrajectoryPredictor|TelemetryCoordinator|TelemetryRepository|LiveVectorResolver|TemporalMemoryEngine|OnlineParameterAdaptationEngine'
echo

echo
echo "================ PACKAGE NAMES ================="
grep -RIn '^package ' . | grep -E \
'MemoryStabilityOptimizer|FrameDropStabilizer|VsyncInputAnchor|LatencyDefeatingInputEngine|HybridResponseCompensationEngine|SpeedCompensationEngine|MotionTracker|SceneTracker|BallTrajectoryPredictor|GoalkeeperTrajectoryPredictor|TelemetryCoordinator|TelemetryRepository|LiveVectorResolver|TemporalMemoryEngine|OnlineParameterAdaptationEngine'
echo

echo
echo "================ PUBLIC FUNCTIONS ================="
for n in \
MemoryStabilityOptimizer \
FrameDropStabilizer \
VsyncInputAnchor \
LatencyDefeatingInputEngine \
HybridResponseCompensationEngine \
SpeedCompensationEngine \
MotionTracker \
SceneTracker \
BallTrajectoryPredictor \
GoalkeeperTrajectoryPredictor \
TelemetryCoordinator \
TelemetryRepository \
LiveVectorResolver \
TemporalMemoryEngine \
OnlineParameterAdaptationEngine
do
find . -type f | grep "$n" | while read f
do
echo
echo "############################################################"
echo "$f"
echo "############################################################"
grep -nE '^(class|object|fun|interface|data class|enum class|sealed class|companion object)' "$f" || true
echo
nl -ba "$f"
done
done

echo
echo "================ IMPORTS ================="
grep -RInE \
'import .*MemoryStabilityOptimizer|import .*FrameDropStabilizer|import .*VsyncInputAnchor|import .*LatencyDefeatingInputEngine|import .*HybridResponseCompensationEngine|import .*SpeedCompensationEngine|import .*MotionTracker|import .*SceneTracker|import .*BallTrajectoryPredictor|import .*GoalkeeperTrajectoryPredictor|import .*TelemetryCoordinator|import .*TelemetryRepository|import .*LiveVectorResolver|import .*TemporalMemoryEngine|import .*OnlineParameterAdaptationEngine' \
.

echo
echo "================ CALL SITES ================="
grep -RInE \
'MemoryStabilityOptimizer|FrameDropStabilizer|VsyncInputAnchor|LatencyDefeatingInputEngine|HybridResponseCompensationEngine|SpeedCompensationEngine|MotionTracker|SceneTracker|BallTrajectoryPredictor|GoalkeeperTrajectoryPredictor|TelemetryCoordinator|TelemetryRepository|LiveVectorResolver|TemporalMemoryEngine|OnlineParameterAdaptationEngine' \
.

echo
echo "================ GIT STATUS ================="
git status --short

} > "$OUTFILE"

echo
echo "AUDIT COMPLETE"
echo "$OUTFILE"

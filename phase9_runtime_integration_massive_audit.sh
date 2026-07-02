#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUTFILE="$OUTDIR/PHASE9_RUNTIME_INTEGRATION_MASSIVE_AUDIT.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

{
echo "================ PHASE 9 RUNTIME INTEGRATION MASSIVE AUDIT ================"
echo "DATE: $(date)"
echo
echo "OBJECTIVE"
echo "Verify every runtime hook for newly created Phase 9 engines."
echo
echo "TARGETS"
echo "- VisionConfigurationEngine"
echo "- TrackingConfigurationEngine"
echo "- RuntimeTuningPanel"
echo "- VisionDebugOverlay"
echo "- VisionOverlayRegistry"
echo "- RuntimeOverlayHub"
echo "- RuntimeDiagnosticsRegistry"
echo "- RuntimeVisualizationRegistry"
echo "- BoundingBoxOverlay"
echo "- BallOverlay"
echo "- PlayerOverlay"
echo "- GoalOverlay"
echo "- FPSMonitor"
echo "- VisionLatencyMonitor"
echo "- ConfidenceHeatmap"
echo "- RuntimePerformanceCoordinator"
echo

echo "================ FILE INVENTORY ================="
find adapter_smartassist/src/main/java/com/assistant/adapter/smartassist -type f | sort
echo

echo "================ ENGINE CROSS REFERENCES ================="
grep -RInE \
'VisionConfigurationEngine|TrackingConfigurationEngine|RuntimeTuningPanel|VisionDebugOverlay|VisionOverlayRegistry|RuntimeOverlayHub|RuntimeDiagnosticsRegistry|RuntimeVisualizationRegistry|BoundingBoxOverlay|BallOverlay|PlayerOverlay|GoalOverlay|FPSMonitor|VisionLatencyMonitor|ConfidenceHeatmap|RuntimePerformanceCoordinator' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist 2>/dev/null || true
echo

echo "================ VISIONCORE INTEGRATION ================="
grep -RInE \
'VisionConfiguration|TrackingConfiguration|RuntimePerformanceCoordinator|FPSMonitor|VisionLatencyMonitor|ConfidenceHeatmap|VisionDebugOverlay|BoundingBoxOverlay|BallOverlay|PlayerOverlay|GoalOverlay|RuntimeDiagnosticsRegistry' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt 2>/dev/null || true
echo

echo "================ ACTIVEGESTURECONTROLLER ================="
grep -RInE \
'VisionConfiguration|TrackingConfiguration|RuntimePerformanceCoordinator|VisionOverlayRegistry|RuntimeOverlayHub|RuntimeDiagnosticsRegistry|RuntimeVisualizationRegistry|FPSMonitor|VisionLatencyMonitor|ConfidenceHeatmap' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt 2>/dev/null || true
echo

echo "================ TELEMETRY / WORLD STATE ================="
grep -RInE \
'Telemetry|TelemetryRepository|TelemetryCoordinator|Phase3WorldState|Phase3WorldStateStore|TemporalMemory|RuntimeConfidenceCalibration|OnlineParameterAdaptation|FPSMonitor|VisionLatencyMonitor|ConfidenceHeatmap' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist 2>/dev/null || true
echo

echo "================ EXISTING PERFORMANCE ENGINES ================="
grep -RInE \
'FrameDrop|FrameDropCompensation|FrameDropStabilizer|SpeedCompensation|HybridResponseCompensation|LatencyDefeatingInputEngine|LowLatencyNetworkThread|NativePipelineCache|MemoryStabilityOptimizer|VsyncInputAnchor' \
adapter_smartassist/src/main/java 2>/dev/null || true
echo

echo "================ COMPLETE SOURCE DUMP ================="
for f in \
VisionConfiguration.kt \
TrackingConfiguration.kt \
RuntimeTuningPanel.kt \
VisionDebugOverlay.kt \
VisionOverlayRegistry.kt \
RuntimeOverlayHub.kt \
RuntimeDiagnosticsRegistry.kt \
RuntimeVisualizationRegistry.kt \
BoundingBoxOverlay.kt \
BallOverlay.kt \
PlayerOverlay.kt \
GoalOverlay.kt \
FPSMonitor.kt \
VisionLatencyMonitor.kt \
ConfidenceHeatmap.kt \
RuntimePerformanceCoordinator.kt \
VisionCore.kt \
ActiveGestureController.kt
do
FILE="adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/$f"
[ -f "$FILE" ] || continue
echo
echo "################################################################"
echo "FILE: $FILE"
echo "################################################################"
nl -ba "$FILE"
echo
done

echo "================ GIT STATUS ================="
git status --short
echo

echo "================ LAST 15 COMMITS ================="
git log --oneline -15
echo

echo "================ END AUDIT ================="

} > "$OUTFILE"

echo
echo "AUDIT COMPLETE"
echo "$OUTFILE"

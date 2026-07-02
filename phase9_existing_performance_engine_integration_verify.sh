#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

grep -RIn "PHASE9_EXISTING_ENGINE_INTEGRATION_MARKER" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

grep -RIn "synchronizeExistingPerformanceEngines" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

grep -RInE \
'MemoryStabilityOptimizer|FrameDropStabilizer|VsyncInputAnchor|LatencyDefeatingInputEngine|HybridResponseCompensationEngine|SpeedCompensationEngine|MotionTracker|SceneTracker|BallTrajectoryPredictor|GoalkeeperTrajectoryPredictor|TelemetryCoordinator|TelemetryRepository|LiveVectorResolver|TemporalMemoryEngine|OnlineParameterAdaptationEngine' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimePerformanceCoordinator.kt

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 EXISTING PERFORMANCE ENGINE INTEGRATION VERIFIED"

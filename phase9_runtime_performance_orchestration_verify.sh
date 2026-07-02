#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

grep -RIn "PHASE9_RUNTIME_PERFORMANCE_ORCHESTRATION_MARKER" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

grep -RIn "synchronizeRuntimePipeline" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 RUNTIME PERFORMANCE ORCHESTRATION VERIFIED"

#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

grep -n "PHASE9_EXISTING_ENGINE_INTEGRATION_MARKER" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimePerformanceCoordinator.kt

grep -n "fun synchronizeExistingPerformanceEngines" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimePerformanceCoordinator.kt

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PATCH VERIFIED"

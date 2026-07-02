#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeVisualizationRegistry.kt

grep -n "object RuntimeVisualizationRegistry" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeVisualizationRegistry.kt

grep -n "enableVisualization" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeVisualizationRegistry.kt

grep -n "disableVisualization" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeVisualizationRegistry.kt

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 RUNTIME VISUALIZATION REGISTRY VERIFIED"

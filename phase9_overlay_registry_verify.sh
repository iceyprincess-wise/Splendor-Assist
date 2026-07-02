#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionOverlayRegistry.kt

grep -n "object VisionOverlayRegistry" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionOverlayRegistry.kt

grep -n "enableAll" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionOverlayRegistry.kt

grep -n "disableAll" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionOverlayRegistry.kt

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 OVERLAY REGISTRY VERIFIED"

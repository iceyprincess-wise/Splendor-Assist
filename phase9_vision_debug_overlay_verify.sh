#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionDebugOverlay.kt

grep -n "object VisionDebugOverlay" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionDebugOverlay.kt

grep -n "VisionDebugOverlayState" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionDebugOverlay.kt

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 VISION DEBUG OVERLAY VERIFIED"

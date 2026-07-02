#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

grep -n "val vision: VisionConfiguration" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionOverlayRegistry.kt

grep -n "val tracking: TrackingConfiguration" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionOverlayRegistry.kt

grep -n "val overlay: VisionDebugOverlayState" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionOverlayRegistry.kt

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PATCH VERIFIED"

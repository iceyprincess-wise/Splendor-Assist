#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

grep -RIn "selectVisionAdaptiveMode" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

grep -RIn "AdaptiveModeAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist

grep -RIn "visionProximityConfidence" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "PATCH VERIFIED"

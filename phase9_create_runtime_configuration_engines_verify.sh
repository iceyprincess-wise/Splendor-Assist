#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionConfiguration.kt

test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TrackingConfiguration.kt

grep -n "object VisionConfigurationEngine" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionConfiguration.kt

grep -n "object TrackingConfigurationEngine" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TrackingConfiguration.kt

./gradlew assembleDebug

echo
echo "PHASE9 CONFIGURATION ENGINES VERIFIED"

#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT"

echo "===== VERIFY ====="

for f in \
FPSMonitor \
VisionLatencyMonitor \
ConfidenceHeatmap
do
    test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/${f}.kt
    grep -n "object ${f}" \
        adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/${f}.kt
done

./gradlew :adapter_smartassist:compileDebugKotlin --configuration-cache

echo
echo "PHASE9 PERFORMANCE MONITORS VERIFIED"

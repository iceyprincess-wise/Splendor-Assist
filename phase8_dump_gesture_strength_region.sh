#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

OUT=/sdcard/SplendorAssist-Audits/PHASE8_GESTURE_STRENGTH_REGION.txt
mkdir -p "$(dirname "$OUT")"

{
echo "===== gestureVisionAuthority ====="
grep -n "gestureVisionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
echo

echo "===== gestureMotionAuthority ====="
grep -n "gestureMotionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
echo

echo "===== telemetryAuthority ====="
grep -n "telemetryAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
echo

echo "===== telemetryBoost ====="
grep -n "telemetryBoost" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
echo

echo "===== strength ====="
grep -n "val strength =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt || true
echo

echo "===== SOURCE 220-360 ====="
perl -ne 'print if $.>=220 && $.<=360' \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
} > "$OUT"

echo
echo "AUDIT COMPLETE"
echo "$OUT"

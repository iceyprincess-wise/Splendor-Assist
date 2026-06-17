#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== ACCESSIBILITY ====="
rg -n \
'AccessibilityService|SmartAssistAccessibilityEngine|onAccessibilityEvent|onInterrupt' \
app adapter_* \
--glob '!build/**'

echo
echo "===== OVERLAY ====="
rg -n \
'OverlayService|WindowManager|addView|removeView|overlay' \
app adapter_* \
--glob '!build/**'

echo
echo "===== MANIFEST ====="
rg -n \
'BIND_ACCESSIBILITY_SERVICE|SYSTEM_ALERT_WINDOW' \
app/src/main/AndroidManifest.xml

echo
echo "PHASE4H READY"

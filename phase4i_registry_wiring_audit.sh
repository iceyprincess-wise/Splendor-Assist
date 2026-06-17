#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== PROCESS SURVIVAL ====="
rg -n \
'ProcessSurvivalRegistry' \
app adapter_* diagnostic_core \
--glob '!build/**' || true

echo
echo "===== RESOURCE BUDGET ====="
rg -n \
'ResourceBudgetRegistry' \
app adapter_* diagnostic_core \
--glob '!build/**' || true

echo
echo "===== SELF AUDIT ====="
rg -n \
'SelfAuditRegistry' \
app adapter_* diagnostic_core \
--glob '!build/**' || true

echo
echo "===== ACCESSIBILITY SURVIVAL ====="
rg -n \
'onServiceConnected|onInterrupt|globalInstance' \
app/src/main/java/com/assistant/overlay/interceptor \
--glob '!build/**'

echo
echo "===== OVERLAY SURVIVAL ====="
rg -n \
'addView|removeView|onDestroy|updateOverlayVisuals' \
app/src/main/java/com/assistant/OverlayService.kt

echo
echo
echo "PHASE4I AUDIT COMPLETE"

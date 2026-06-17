#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== PROCESS ====="
rg -n 'ProcessSurvivalRegistry' \
app adapter_* \
--glob '!build/**'

echo
echo "===== RESOURCE ====="
rg -n 'ResourceBudgetRegistry' \
app adapter_* \
--glob '!build/**'

echo
echo "===== AUDIT ====="
rg -n 'SelfAuditRegistry' \
app adapter_* \
--glob '!build/**'

echo
echo "===== ACCESSIBILITY ====="
rg -n \
'AccessibilitySurvivalEngine' \
app \
--glob '!build/**'

echo
echo "===== OVERLAY ====="
rg -n \
'OverlaySurvivalEngine' \
app \
--glob '!build/**'

echo
echo "PHASE4J VERIFIED"

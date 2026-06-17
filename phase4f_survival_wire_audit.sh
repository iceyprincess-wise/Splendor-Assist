#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== SURVIVAL ====="
rg -n \
'ProcessSurvivalRegistry|ResourceBudgetRegistry' \
app/src/main/java \
--glob '!build/**'

echo
echo "===== AUDIT ====="
rg -n \
'SelfAuditRegistry' \
app/src/main/java \
--glob '!build/**'

echo
echo "PHASE4F READY"

#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== WATCHDOG ====="
rg -n \
'ProcessSurvivalRegistry' \
adapter_watchdog \
--glob '!build/**'

echo
echo "===== SCHEDULER ====="
rg -n \
'ResourceBudgetRegistry' \
adapter_scheduler \
--glob '!build/**'

echo
echo "===== RECOVERY ====="
rg -n \
'SelfAuditRegistry' \
app/src/main/java/com/assistant/recovery \
--glob '!build/**'

echo
echo "===== DASHBOARD ====="
rg -n \
'ProcessSurvivalRegistry|ResourceBudgetRegistry|SelfAuditRegistry' \
app/src/main/java/com/assistant/DashboardInjector.kt

echo
echo "===== BUILD ====="
./gradlew assembleDebug

echo
echo "PHASE4G VERIFIED"

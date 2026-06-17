#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== HEALTH REGISTRY ====="
rg -n 'effectiveStatus|healthPercent|AdapterHealthSnapshot' \
diagnostic_core app adapter_* \
--glob '!build/**'

echo
echo "===== RECOVERY ====="
rg -n 'recordAttempt|recordSuccess|setOfflineAdapters' \
app/src/main/java/com/assistant/recovery \
--glob '!build/**'

echo
echo "===== WATCHDOG ====="
rg -n 'WATCHDOG OFFLINE|WATCHDOG DEGRADED' \
adapter_watchdog \
--glob '!build/**'

echo
echo "===== SCHEDULER ====="
rg -n 'FLEET HEALTH' \
adapter_scheduler \
--glob '!build/**'

echo
echo "===== DASHBOARD ====="
rg -n 'DashboardInjector|RuntimeMetricsRegistry' \
app/src/main/java/com/assistant \
--glob '!build/**'

echo
echo "ENGINE FOUNDATION READY"

#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== ADAPTER COUNT ====="
find adapter_* -type f -name '*Service.kt' | wc -l

echo
echo "===== HEALTH USERS ====="
rg -n 'AdapterHealthRegistry' \
adapter_* app diagnostic_core \
--glob '!build/**'

echo
echo "===== RECOVERY USERS ====="
rg -n 'RecoveryMetricsRegistry' \
app \
--glob '!build/**'

echo
echo "===== DASHBOARD USERS ====="
rg -n 'DashboardInjector|RuntimeMetricsRegistry' \
app \
--glob '!build/**'

echo
echo "PHASE4E READY"

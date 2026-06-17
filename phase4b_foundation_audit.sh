#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== DASHBOARD ====="
rg -n 'RuntimeMetricsRegistry|RecoveryMetricsRegistry' \
app/src/main/java/com/assistant \
--glob '!build/**' || true

echo
echo "===== HEALTH ====="
rg -n 'effectiveStatus|healthPercent' \
app/src/main/java \
diagnostic_core \
--glob '!build/**' || true

echo
echo "===== RECOVERY ====="
rg -n 'launchAdapter|recordSuccess|recordAttempt' \
app/src/main/java/com/assistant/recovery \
--glob '!build/**' || true

echo
echo "PHASE4B AUDIT COMPLETE"

#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== BOOT ====="
grep -n 'BOOT uptime=' \
adapter_boot/src/main/java/com/assistant/adapter/boot/BootAdapterService.kt

echo
echo "===== WATCHDOG ====="
grep -n 'WATCHDOG OFFLINE\|WATCHDOG DEGRADED' \
adapter_watchdog/src/main/java/com/assistant/adapter/watchdog/WatchdogAdapterService.kt

echo
echo "===== SCHEDULER ====="
grep -n 'FLEET HEALTH' \
adapter_scheduler/src/main/java/com/assistant/adapter/scheduler/SchedulerAdapterService.kt

echo
echo "===== RECOVERY ====="
grep -n 'launchAdapter\|recordSuccess\|recordAttempt' \
app/src/main/java/com/assistant/recovery/AdapterRecoveryEngine.kt

echo
echo "PHASE3X VERIFIED"

#!/data/data/com.termux/files/usr/bin/bash
set -e

echo
echo "===== ACCESSIBILITY ====="
sed -n '1,220p' \
app/src/main/java/com/assistant/overlay/interceptor/SmartAssistAccessibilityEngine.kt

echo
echo "===== OVERLAY ====="
sed -n '130,380p' \
app/src/main/java/com/assistant/OverlayService.kt

echo
echo "===== WATCHDOG ====="
sed -n '1,220p' \
adapter_watchdog/src/main/java/com/assistant/adapter/watchdog/WatchdogAdapterService.kt

echo
echo "===== DASHBOARD ====="
sed -n '1,220p' \
app/src/main/java/com/assistant/DashboardInjector.kt

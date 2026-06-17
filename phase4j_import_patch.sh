#!/data/data/com.termux/files/usr/bin/bash
set -e

perl -0777 -i -pe '
s/import com\.assistant\.diagnostic\.registry\.AdapterHealthSnapshot/import com.assistant.diagnostic.registry.AdapterHealthSnapshot\nimport com.assistant.survival.ProcessSurvivalRegistry/s
' \
adapter_watchdog/src/main/java/com/assistant/adapter/watchdog/WatchdogAdapterService.kt

perl -0777 -i -pe '
s/import com\.assistant\.diagnostic\.registry\.AdapterHealthSnapshot/import com.assistant.diagnostic.registry.AdapterHealthSnapshot\nimport com.assistant.survival.ResourceBudgetRegistry/s
' \
adapter_scheduler/src/main/java/com/assistant/adapter/scheduler/SchedulerAdapterService.kt

echo "PATCH COMPLETE"

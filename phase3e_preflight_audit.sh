#!/data/data/com.termux/files/usr/bin/bash

echo "===== IGNITION ====="
sed -n '1,260p' \
app/src/main/java/com/assistant/IgnitionEngine.kt

echo
echo "===== RECOVERY ====="
sed -n '1,260p' \
app/src/main/java/com/assistant/recovery/AdapterRecoveryEngine.kt

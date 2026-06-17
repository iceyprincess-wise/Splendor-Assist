#!/data/data/com.termux/files/usr/bin/bash
set -e

FILE="app/src/main/java/com/assistant/recovery/AdapterRecoveryEngine.kt"

perl -0pi -e '
s/"Recovery verified :: ",\s*
\s*"RECOVERY"/"Recovery verified :: \${snapshot.adapterName}",
                                            "RECOVERY"/s
' "$FILE"

echo
echo "PHASE3G LOG FIXED"

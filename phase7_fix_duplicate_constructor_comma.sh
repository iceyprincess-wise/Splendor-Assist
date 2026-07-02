#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
FILE="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt"

python3 <<'PY'
from pathlib import Path
import re

f=Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt"
s=f.read_text(encoding="utf-8")

old="""shootingHabitLearningResult = shootingHabitLearningResult,,"""
new="""shootingHabitLearningResult = shootingHabitLearningResult,"""

if old not in s:
    s=re.sub(
        r'(shootingHabitLearningResult\s*=\s*shootingHabitLearningResult),{2,}',
        r'\1,',
        s
    )
else:
    s=s.replace(old,new,1)

f.write_text(s,encoding="utf-8")
PY

echo
echo "========== VERIFY =========="
nl -ba "$FILE" | sed -n '394,401p'

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin

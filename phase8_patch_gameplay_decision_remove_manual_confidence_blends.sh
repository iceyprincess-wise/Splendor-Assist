#!/data/data/com.termux/files/usr/bin/bash
set -e

cd "$HOME/projects/Splendor-Assist"

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt")
s=p.read_text()

# Remove legacy manual temporal blend if present
s=re.sub(
r'''(?ms)
\s*val\s+confidence\s*=\s*
\(\s*baseConfidence\s*\+\s*temporalDecisionConfidence\s*\)\s*/\s*2f
\s*''',
'\n',
s,
count=1
)

# Replace any remaining confidence assignment with adaptive confidence
s=re.sub(
r'''val\s+confidence\s*=.*''',
'''val confidence =
            (
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                adaptiveConfidence +
                (1f - temporal.confidenceVariance).coerceIn(0f,1f) +
                (0.5f + temporal.confidenceTrend * 0.5f).coerceIn(0f,1f)
            ).div(6f).coerceIn(0f,1f)''',
s,
count=1
)

# Remove remaining references to temporalDecisionConfidence
s=re.sub(
r'''(?ms)\n\s*val\s+temporalDecisionConfidence\s*=.*?(?=\n\s*val|\n\s*return|\n\s*when|\n\s*[A-Za-z_])''',
'\n',
s
)

p.write_text(s)
PY

echo "========== VERIFY =========="
grep -n "val confidence" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "adaptiveConfidence" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "temporal.temporalConfidence" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "confidenceVariance" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "confidenceTrend" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin

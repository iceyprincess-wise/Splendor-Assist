#!/data/data/com.termux/files/usr/bin/bash
set -e

cd "$HOME/projects/Splendor-Assist"

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt")
s=p.read_text()

pattern=r'''(?s)        val baseConfidence =.*?        val priority ='''

replacement="""        val baseConfidence =
            (
                when (mode) {
                    2 -> shotScore
                    1 -> passScore
                    else -> crossScore
                }.coerceAtMost(100f) / 100f
            )

        val adaptiveConfidence =
            (
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                temporal.historyStability +
                (1f - temporal.confidenceVariance).coerceIn(0f,1f) +
                (0.5f + temporal.confidenceTrend * 0.5f).coerceIn(0f,1f)
            ) / 6f

        val confidence =
            (
                baseConfidence +
                adaptiveConfidence
            ) / 2f

        val priority ="""

new,count=re.subn(pattern,replacement,s,count=1)

if count!=1:
    raise SystemExit("Failed to uniquely replace corrupted confidence block.")

p.write_text(new)
PY

echo "========== VERIFY =========="
nl -ba adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt | sed -n '58,95p'

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin

#!/data/data/com.termux/files/usr/bin/bash
set -e

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

FILE = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt")
src = FILE.read_text()

pattern = re.compile(
    r'(?ms)val\s+confidence=\(\(when\)\+temporalDecisionConfidence\)/2f\s*\(mode\)\s*\{.*?else\s*->\s*crossScore\s*\}\.coerceAtMost\(100f\)\s*/\s*100f'
)

replacement = """val baseConfidence =
            (
                when (mode) {
                    2 -> shotScore
                    1 -> passScore
                    else -> crossScore
                }.coerceAtMost(100f) / 100f
            )

        val temporalDecisionConfidence =
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
                temporalDecisionConfidence
            ) / 2f"""

new_src, count = pattern.subn(replacement, src, count=1)

if count != 1:
    raise SystemExit("FAILED: Broken temporal confidence block not found exactly once.")

FILE.write_text(new_src)
print("GameplayDecisionEngine repaired.")
PY

echo "========== VERIFY =========="
grep -n "val baseConfidence" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt || true

grep -n "val temporalDecisionConfidence" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt || true

grep -n "val confidence =" \
adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt || true

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin

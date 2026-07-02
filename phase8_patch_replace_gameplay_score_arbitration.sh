#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re

p=Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt")
s=p.read_text()

old=r'''(?s)        val baseConfidence =
            \(
                when \(mode\) \{
                    2 -> shotScore
                    1 -> passScore
                    else -> crossScore
                \}\.coerceAtMost\(100f\) / 100f
            \)

        val adaptiveConfidence =
            \(
                temporal\.temporalConfidence \+
                temporal\.exponentialMovingAverage \+
                temporal\.rollingMean \+
                temporal\.historyStability \+
                \(1f - temporal\.confidenceVariance\)\.coerceIn\(0f,1f\) \+
                \(0\.5f \+ temporal\.confidenceTrend \* 0\.5f\)\.coerceIn\(0f,1f\)
            \) / 6f

        val confidence =
            \(
                baseConfidence \+
                adaptiveConfidence
            \) / 2f'''

new='''        val normalizedShotAuthority =
            (shotScore / 100f).coerceIn(0f,1f)

        val normalizedPassAuthority =
            (passScore / 100f).coerceIn(0f,1f)

        val normalizedCrossAuthority =
            (crossScore / 100f).coerceIn(0f,1f)

        val adaptiveAuthority =
            (
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                temporal.historyStability +
                (1f - temporal.confidenceVariance).coerceIn(0f,1f) +
                (0.5f + temporal.confidenceTrend * 0.5f).coerceIn(0f,1f)
            ) / 6f

        val visionAuthority =
            when (mode) {
                2 -> normalizedShotAuthority
                1 -> normalizedPassAuthority
                else -> normalizedCrossAuthority
            }

        val confidence =
            (
                visionAuthority * 0.80f +
                adaptiveAuthority * 0.20f
            ).coerceIn(0f,1f)'''

s,n=re.subn(old,new,s,count=1)
if n!=1:
    raise SystemExit("FAILED locating confidence arbitration block")

p.write_text(s)
print("PATCHED:",p)
PY

echo
echo "========== VERIFY =========="
grep -n "normalizedShotAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "normalizedPassAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "normalizedCrossAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "adaptiveAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "visionAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt
grep -n "val confidence =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin

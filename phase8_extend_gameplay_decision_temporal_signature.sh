#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

cd "$ROOT"

python3 <<'PY'
from pathlib import Path
import re

game = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GameplayDecisionEngine.kt")
ctrl = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")

g = game.read_text()

# ------------------------------------------------------------------
# Extend decide(...) signature once
# ------------------------------------------------------------------
if "temporal: TemporalMemoryState" not in g:
    pat = re.compile(r'fun\s+decide\s*\((.*?)\)', re.S)
    m = pat.search(g)
    if not m:
        raise SystemExit("decide(...) signature not found")

    params = m.group(1).rstrip()

    if params.endswith(","):
        repl = params + "\n        temporal: TemporalMemoryState"
    else:
        repl = params + ",\n        temporal: TemporalMemoryState"

    g = g[:m.start(1)] + repl + g[m.end(1):]

# ------------------------------------------------------------------
# Create reusable temporal confidence once
# ------------------------------------------------------------------
if "val temporalDecisionConfidence" not in g:
    m = re.search(r'fun\s+decide\s*\(.*?\)\s*\{', g, re.S)
    if not m:
        raise SystemExit("decide body not found")

    insert = '''

        val temporalDecisionConfidence =
            (
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                temporal.historyStability +
                (1f - temporal.confidenceVariance).coerceIn(0f,1f) +
                (0.5f + temporal.confidenceTrend * 0.5f).coerceIn(0f,1f)
            ) / 6f

'''
    g = g[:m.end()] + insert + g[m.end():]

game.write_text(g)

c = ctrl.read_text()

# ------------------------------------------------------------------
# Wire caller once
# ------------------------------------------------------------------
if "worldState.temporalMemoryState" not in c:
    c = re.sub(
        r'(GameplayDecisionEngine\.decide\s*\([^)]*?)\)',
        lambda m: m.group(1).rstrip() + ",\n                worldState.temporalMemoryState\n            )",
        c,
        count=1,
        flags=re.S
    )

ctrl.write_text(c)
PY

echo "========== VERIFY =========="
grep -n "fun decide" "$PKG/GameplayDecisionEngine.kt"
grep -n "temporal: TemporalMemoryState" "$PKG/GameplayDecisionEngine.kt"
grep -n "temporalDecisionConfidence" "$PKG/GameplayDecisionEngine.kt"
grep -n "GameplayDecisionEngine.decide" "$PKG/ActiveGestureController.kt" -A8

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin

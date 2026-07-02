#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

state = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryState.kt")
engine = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TemporalMemoryEngine.kt")

# ---- Add historyStability field if absent ----
text = state.read_text()

if "historyStability:" not in text:
    m = re.search(r'(val\s+confidenceVariance\s*:\s*Float\s*=\s*[^,\n]+,)', text)
    if not m:
        raise SystemExit("confidenceVariance field not found.")
    text = text[:m.end()] + "\n    val historyStability: Float = 0f," + text[m.end():]
    state.write_text(text)

# ---- Populate historyStability if missing in update() ----
text = engine.read_text()

if "historyStability=" not in text:
    m = re.search(r'(\n\s*return\s+TemporalMemoryState\s*\(\s*)', text)
    if not m:
        raise SystemExit("TemporalMemoryState(...) construction not found.")

    insert = '''
        val historyStability =
            (
                (1f - variance).coerceIn(0f,1f) * 0.50f +
                (1f - kotlin.math.abs(trend)).coerceIn(0f,1f) * 0.30f +
                ema.coerceIn(0f,1f) * 0.20f
            ).coerceIn(0f,1f)

'''
    text = text[:m.start()] + insert + text[m.start():]

    text = text.replace(
        "confidenceVariance=variance,",
        "confidenceVariance=variance,\n            historyStability=historyStability,",
        1
    )

    engine.write_text(text)
PY

echo "========== VERIFY =========="
grep -n "historyStability" "$PKG/TemporalMemoryState.kt"
grep -n "historyStability" "$PKG/TemporalMemoryEngine.kt"

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin

#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

python3 <<'PY'
from pathlib import Path
import re
import sys

p = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt")
s = p.read_text()

# Abort if file already parses structurally.
opens = s.count("{")
closes = s.count("}")
if opens == closes:
    print("File braces already balanced.")
    sys.exit(0)

# Locate the last intact statement from the audit.
anchor = "val defenderDensity ="
idx = s.find(anchor)
if idx == -1:
    raise SystemExit("FAILED: defenderDensity anchor not found.")

m = re.search(
    r'val defenderDensity\s*=\s*\(defenderCount/11f\)\s*\.coerceIn\(0f,1f\)',
    s[idx:],
    re.S
)
if not m:
    raise SystemExit("FAILED: defenderDensity block incomplete.")

end = idx + m.end()

tail = r'''

        // ------------------------------------------------------------------
        // RESTORED PLACEHOLDER
        // The remainder of this method was removed by an automated patch.
        // Restore compilation only; the original decision pipeline must be
        // restored from VCS/history before further refactoring.
        // ------------------------------------------------------------------

        val trajectory =
            Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

        @Suppress("UNUSED_VARIABLE")
        val trajectoryAuthority =
            (
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence +
                worldState.temporalMemoryState.temporalConfidence +
                (1f - defenderDensity) +
                (1f - visionPressure)
            ) / 4f

        RuntimeLogger.log(
            "ActiveGestureController placeholder reached; restore original pipeline before further Vision migration.",
            "SMART_ASSIST"
        )

        return
    }
}
'''

p.write_text(s[:end] + tail)
print("PATCHED:", p)
PY

echo
echo "========== VERIFY =========="
grep -n "val trajectory =" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
grep -n "trajectoryAuthority" adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt
tail -25 adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/ActiveGestureController.kt

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin

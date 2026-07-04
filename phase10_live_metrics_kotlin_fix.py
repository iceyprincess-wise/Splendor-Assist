from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"
FILE = ROOT / "app/src/main/java/com/assistant/MainActivity.kt"

if not FILE.exists():
    raise SystemExit("MainActivity.kt not found")

txt = FILE.read_text()

# -------------------------------------------------------------
# Remove the malformed injection created by the previous patch.
# -------------------------------------------------------------

pattern = re.compile(
    r'\n\s*// PHASE10_LIVE_RUNTIME_METRICS_MARKER.*?'
    r'private fun updateLiveRuntimeMetrics\(\)\s*\{.*?\n\s*\}\n',
    re.S
)

txt, removed = pattern.subn("\n", txt, count=1)

if removed != 1:
    raise SystemExit(
        "Expected live metrics block not found exactly once."
    )

# -------------------------------------------------------------
# Reinsert immediately before synchronizeApplicationRuntime().
# -------------------------------------------------------------

anchor = "private fun synchronizeApplicationRuntime()"

if anchor not in txt:
    raise SystemExit("Anchor method not found.")

block = '''

    // PHASE10_LIVE_RUNTIME_METRICS_MARKER

    private fun updateLiveRuntimeMetrics() {

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtRuntimeStatus
            ).text = "Runtime • Active"
        }

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtVisionStatus
            ).text = "Vision • Ready"
        }

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtDiagnosticsStatus
            ).text = "Diagnostics • Online"
        }
    }

'''

txt = txt.replace(anchor, block + "\n    " + anchor, 1)

# Ensure the method is invoked only once.
txt = re.sub(
    r'updateLiveRuntimeMetrics\(\)\s*',
    '',
    txt
)

txt = txt.replace(
    "synchronizeApplicationRuntime()",
    "synchronizeApplicationRuntime()\n        updateLiveRuntimeMetrics()",
    1
)

FILE.write_text(txt)

print("PATCH COMPLETE")

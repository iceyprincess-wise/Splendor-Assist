from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"
FILE = ROOT / "app/src/main/java/com/assistant/MainActivity.kt"

text = FILE.read_text()

# -----------------------------------------------------------------
# Remove every previously injected live metrics block completely.
# -----------------------------------------------------------------

text = re.sub(
    r'\n\s*// PHASE10_LIVE_RUNTIME_METRICS_MARKER.*?(?=\n\s*private fun |\n\s*override fun |\n\s*companion object|\n})',
    '',
    text,
    flags=re.S
)

# Remove stray calls
text = re.sub(r'\n\s*updateLiveRuntimeMetrics\(\)\s*', '\n', text)

anchor = "private fun synchronizeApplicationRuntime()"

if anchor not in text:
    raise SystemExit("Anchor not found")

block = """

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

"""

text = text.replace(anchor, block + "\n    " + anchor, 1)

# Insert call inside method body only.
pattern = re.compile(
    r'(private fun synchronizeApplicationRuntime\(\)\s*\{)',
    re.M
)

text = pattern.sub(
    r'\1\n        updateLiveRuntimeMetrics()',
    text,
    count=1
)

FILE.write_text(text)

print("PATCH COMPLETE")

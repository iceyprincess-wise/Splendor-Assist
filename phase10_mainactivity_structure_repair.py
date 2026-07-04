from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"
FILE = ROOT / "app/src/main/java/com/assistant/MainActivity.kt"

text = FILE.read_text()

start = text.find("// PHASE10_NAVIGATION_RUNTIME_MARKER")
end = text.find("// PHASE10_LIVE_RUNTIME_METRICS_MARKER")

if start == -1 or end == -1 or end <= start:
    raise SystemExit("Unable to locate corrupted region.")

replacement = """
    // PHASE10_NAVIGATION_RUNTIME_MARKER

"""

text = text[:start] + replacement + text[end:]

# Remove accidental anonymous function if still present.
text = re.sub(
    r'\n\s*private\s+fun\s*\{\s*.*?\n\s*\}\n',
    '\n',
    text,
    flags=re.S
)

# Balance braces.
opens = text.count("{")
closes = text.count("}")

if closes > opens:
    diff = closes - opens
    while diff:
        idx = text.rfind("}")
        if idx == -1:
            break
        text = text[:idx] + text[idx+1:]
        diff -= 1

FILE.write_text(text)

print("PATCH COMPLETE")

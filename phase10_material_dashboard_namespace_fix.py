from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"
FILE = ROOT / "app/src/main/res/layout/activity_main.xml"

if not FILE.exists():
    raise SystemExit("activity_main.xml not found")

txt = FILE.read_text()

# ------------------------------------------------------------------
# Ensure Material namespace exists.
# ------------------------------------------------------------------

if 'xmlns:app=' not in txt:
    txt = re.sub(
        r'(<ScrollView\b[^>]*xmlns:android="[^"]+")',
        r'\1\n    xmlns:app="http://schemas.android.com/apk/res-auto"',
        txt,
        count=1
    )

# ------------------------------------------------------------------
# Balance MaterialCardView tags.
# ------------------------------------------------------------------

OPEN = "<com.google.android.material.card.MaterialCardView"
CLOSE = "</com.google.android.material.card.MaterialCardView>"

opens = txt.count(OPEN)
closes = txt.count(CLOSE)

if opens == 1 and closes == 0:
    txt = txt.replace(
        "</ScrollView>",
        CLOSE + "\n</ScrollView>",
        1
    )

elif opens != closes:
    raise SystemExit(f"Unbalanced MaterialCardView tags: open={opens} close={closes}")

FILE.write_text(txt)

print("PATCH COMPLETE")

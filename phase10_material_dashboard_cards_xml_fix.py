from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist"
FILE = ROOT / "app/src/main/res/layout/activity_main.xml"

if not FILE.exists():
    raise SystemExit("activity_main.xml not found")

txt = FILE.read_text()

# ------------------------------------------------------------------
# Repair malformed MaterialCardView produced by previous patch.
# ------------------------------------------------------------------

OPEN = '<com.google.android.material.card.MaterialCardView'
CLOSE = '</com.google.android.material.card.MaterialCardView>'

opens = txt.count(OPEN)
closes = txt.count(CLOSE)

if opens == 1 and closes == 0:
    end_scroll = txt.rfind("</ScrollView>")
    if end_scroll == -1:
        raise SystemExit("Closing </ScrollView> not found.")

    txt = txt[:end_scroll] + CLOSE + "\n\n" + txt[end_scroll:]

elif opens != closes:
    raise SystemExit(
        f"Unexpected MaterialCardView count. opens={opens}, closes={closes}"
    )

FILE.write_text(txt)

print("XML REPAIRED")

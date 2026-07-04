from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"

if not LAYOUT.exists():
    raise SystemExit("activity_main.xml not found")

txt = LAYOUT.read_text()

MARKER = "PHASE10_MATERIAL_DASHBOARD_CARD_MARKER"

if MARKER not in txt:

    txt = txt.replace(
        "<LinearLayout",
        '<com.google.android.material.card.MaterialCardView\n'
        '    android:tag="PHASE10_MATERIAL_DASHBOARD_CARD_MARKER"\n'
        '    android:layout_width="match_parent"\n'
        '    android:layout_height="wrap_content"\n'
        '    android:layout_margin="8dp"\n'
        '    app:cardCornerRadius="18dp"\n'
        '    app:cardUseCompatPadding="true">\n\n'
        '    <LinearLayout',
        1
    )

    txt = txt.replace(
        "</LinearLayout>\n</ScrollView>",
        "</LinearLayout>\n"
        "</com.google.android.material.card.MaterialCardView>\n"
        "</ScrollView>",
        1
    )

LAYOUT.write_text(txt)

print("PATCH COMPLETE")

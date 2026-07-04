from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

THEME = ROOT / "app/src/main/res/values/themes.xml"

LAYOUTS = list((ROOT / "app/src/main/res/layout").glob("*.xml"))

if not THEME.exists():
    raise SystemExit("themes.xml not found")

# ----------------------------------------------------------
# THEME -> Material3
# ----------------------------------------------------------

theme = THEME.read_text()

if "PHASE10_MATERIAL3_THEME_MARKER" not in theme:

    theme = theme.replace(
        "Theme.AppCompat.DayNight.NoActionBar",
        "Theme.Material3.DayNight.NoActionBar"
    )

    theme = theme.replace(
        "<style name=\"Theme.SplendorAssist\"",
        "<!-- PHASE10_MATERIAL3_THEME_MARKER -->\n    <style name=\"Theme.SplendorAssist\"",
        1
    )

THEME.write_text(theme)

# ----------------------------------------------------------
# XML modernization
# ----------------------------------------------------------

for xml in LAYOUTS:

    txt = xml.read_text()

    if "PHASE10_MATERIAL3_LAYOUT_MARKER" not in txt:

        txt = txt.replace(
            "<Button",
            "<com.google.android.material.button.MaterialButton"
        )

        txt = txt.replace(
            "</Button>",
            "</com.google.android.material.button.MaterialButton>"
        )

        txt = txt.replace(
            "<Switch",
            "<com.google.android.material.materialswitch.MaterialSwitch"
        )

        txt = txt.replace(
            "</Switch>",
            "</com.google.android.material.materialswitch.MaterialSwitch>"
        )

        txt = txt.replace(
            "<SeekBar",
            "<com.google.android.material.slider.Slider"
        )

        txt = txt.replace(
            "</SeekBar>",
            "</com.google.android.material.slider.Slider>"
        )

        txt = txt.replace(
            "<LinearLayout",
            "<LinearLayout\n    android:tag=\"PHASE10_MATERIAL3_LAYOUT_MARKER\"",
            1
        )

        xml.write_text(txt)

print("PATCH COMPLETE")

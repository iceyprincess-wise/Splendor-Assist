from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"
FILE = ROOT / "app/build.gradle.kts"

if not FILE.exists():
    raise SystemExit("app/build.gradle.kts not found")

txt = FILE.read_text()

# ------------------------------------------------------------------
# Fix Groovy syntax accidentally inserted into Kotlin DSL
# ------------------------------------------------------------------

txt = re.sub(
    r'viewBinding\s+true',
    'viewBinding = true',
    txt
)

txt = re.sub(
    r"implementation\s+'([^']+)'",
    r'implementation("\1")',
    txt
)

# Ensure viewBinding is inside buildFeatures
if "buildFeatures {" in txt and "viewBinding = true" not in txt:
    txt = txt.replace(
        "buildFeatures {",
        "buildFeatures {\n        viewBinding = true",
        1
    )

# Ensure Material dependency exists using Kotlin DSL syntax
if 'implementation("com.google.android.material:material:1.12.0")' not in txt:
    txt = re.sub(
        r'(dependencies\s*\{)',
        r'\1\n    implementation("com.google.android.material:material:1.12.0")',
        txt,
        count=1
    )

FILE.write_text(txt)

print("PATCH COMPLETE")

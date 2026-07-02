#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd ~/projects/Splendor-Assist

MANIFEST="adapter_smartassist/src/main/AndroidManifest.xml"
GRADLE_KTS="adapter_smartassist/build.gradle.kts"
GRADLE="adapter_smartassist/build.gradle"

python3 <<'PY'
from pathlib import Path
import re

manifest = Path("adapter_smartassist/src/main/AndroidManifest.xml")
text = manifest.read_text()

new = re.sub(
    r'\s+package="com\.assistant\.adapter\.smartassist"',
    "",
    text,
    count=1
)

if new == text:
    print("Manifest already clean.")
else:
    manifest.write_text(new)
    print("Removed manifest package attribute.")
PY

echo
echo "========== VERIFY MANIFEST =========="
grep -n "<manifest" "$MANIFEST"

echo
echo "========== VERIFY NAMESPACE =========="
if [ -f "$GRADLE_KTS" ]; then
    grep -n "namespace" "$GRADLE_KTS" || true
fi

if [ -f "$GRADLE" ]; then
    grep -n "namespace" "$GRADLE" || true
fi

echo
echo "========== BUILD =========="
./gradlew :adapter_smartassist:compileDebugKotlin

#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/projects/Splendor-Assist"
PKG="$ROOT/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

python3 <<'PY'
from pathlib import Path
import re

f=Path.home()/"projects/Splendor-Assist"/"adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt"
src=f.read_text()

m=re.search(r'Phase3WorldState\s*\(',src)
if not m:
    raise SystemExit("Phase3WorldState(...) not found.")

start=m.end()
depth=1
i=start

while i < len(src):
    c=src[i]
    if c=="(":
        depth+=1
    elif c==")":
        depth-=1
        if depth==0:
            end=i
            break
    i+=1
else:
    raise SystemExit("Constructor end not found.")

body=src[start:end]

lines=[x.rstrip() for x in body.splitlines()]

clean=[]
for line in lines:
    if line.strip():
        clean.append(line)

# Remove trailing commas from every argument.
clean=[re.sub(r',\s*$','',x) for x in clean]

# Rebuild with commas only between arguments.
rebuilt=[]
for idx,line in enumerate(clean):
    if idx < len(clean)-1:
        rebuilt.append(line+",")
    else:
        rebuilt.append(line)

newbody="\n".join(rebuilt)

src=src[:start]+"\n"+newbody+"\n"+src[end:]

f.write_text(src)
PY

echo
echo "========== VERIFY =========="
nl -ba "$PKG/VisionCore.kt" | sed -n '385,405p'

echo
echo "========== BUILD =========="
cd "$ROOT"
./gradlew :adapter_smartassist:compileDebugKotlin

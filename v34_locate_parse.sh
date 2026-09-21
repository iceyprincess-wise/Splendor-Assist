#!/usr/bin/env bash
set +e

cd ~/projects/Splendor-Assist || exit 1

DL="$HOME/storage/downloads"
DL_RESOLVED="$(readlink -f "$DL" 2>/dev/null || printf '%s' "$DL")"

ROOTS=(
  "$DL"
  "$DL_RESOLVED"
  "/sdcard/Download"
  "/storage/emulated/0/Download"
  "$HOME/projects/Splendor-Assist"
)

OUT_DIR="$DL"
mkdir -p "$OUT_DIR" 2>/dev/null

echo "================ V34 ROBUST LOCATOR ================"
date

echo
echo "=== STORAGE DIAG ==="
echo "DL=$DL"
echo "DL_RESOLVED=$DL_RESOLVED"
ls -ld "$DL" 2>/dev/null || true
ls -ld "$DL_RESOLVED" 2>/dev/null || true

echo
echo "=== DOWNLOADS FILTER ==="
ls -la "$DL" 2>/dev/null | grep -E 'Splendor_V33|Splendor_V31A|Field_Logs|V31A_Live_Proof' | head -n 80 || true
ls -la "$DL_RESOLVED" 2>/dev/null | grep -E 'Splendor_V33|Splendor_V31A|Field_Logs|V31A_Live_Proof' | head -n 80 || true

find_one() {
  local pat="$1"
  local tmp=".v34_find_one_tmp"
  : > "$tmp"

  for r in "${ROOTS[@]}"; do
    [ -e "$r" ] || continue
    find -L "$r" -maxdepth 7 -type f -name "$pat" -print0 2>/dev/null >> "$tmp"
  done

  if [ -s "$tmp" ]; then
    xargs -0 -r ls -t < "$tmp" 2>/dev/null | head -n 1
  fi

  rm -f "$tmp"
}

find_multi() {
  local tmp=".v34_find_multi_tmp"
  : > "$tmp"

  for pat in "$@"; do
    for r in "${ROOTS[@]}"; do
      [ -e "$r" ] || continue
      find -L "$r" -maxdepth 7 -type f -name "$pat" -print0 2>/dev/null >> "$tmp"
    done
  done

  if [ -s "$tmp" ]; then
    xargs -0 -r ls -t < "$tmp" 2>/dev/null | head -n 1
  fi

  rm -f "$tmp"
}

V33="$(find_one '*V33_Contributor_Contract_Trace*.txt')"
V31A="$(find_multi \
  '*V31A_Live_Proof*.txt' \
  '*Field_Logs*V31A*.txt' \
  '*There_is_instruction_at_its_top.txt' \
  '*Splendor_Field_Logs*.txt')"

echo
echo "V33_FOUND=${V33:-MISSING}"
echo "V31A_FOUND=${V31A:-MISSING}"

if [ -n "$V33" ] && [ -f "$V33" ]; then
  echo
  echo "V33 SIZE:"
  wc -c "$V33" 2>/dev/null || true
  sha256sum "$V33" 2>/dev/null || true
fi

if [ -n "$V31A" ] && [ -f "$V31A" ]; then
  echo
  echo "V31A SIZE:"
  wc -c "$V31A" 2>/dev/null || true
  sha256sum "$V31A" 2>/dev/null || true
fi

if [ -z "$V33" ] || [ ! -f "$V33" ]; then
  echo
  echo "=== V33 DIAGNOSTIC SEARCH ==="
  for r in "${ROOTS[@]}"; do
    [ -e "$r" ] || continue
    echo "ROOT: $r"
    find -L "$r" -maxdepth 7 -type f -name '*V33*' -print 2>/dev/null | head -n 80 || true
  done
fi

if [ -z "$V31A" ] || [ ! -f "$V31A" ]; then
  echo
  echo "=== V31A DIAGNOSTIC SEARCH ==="
  for r in "${ROOTS[@]}"; do
    [ -e "$r" ] || continue
    echo "ROOT: $r"
    find -L "$r" -maxdepth 7 -type f \( -name '*V31A*' -o -name '*Field_Logs*' -o -name '*There_is_instruction*' \) -print 2>/dev/null | head -n 120 || true
  done
fi

# ------------------------------------------------------------------
# TODO lock update
# ------------------------------------------------------------------
if [ -f Splendor_TODO.md ] && ! grep -q '## V34 Storage Symlink Correction' Splendor_TODO.md; then
  cat >> Splendor_TODO.md <<'MD'

## V34 Storage Symlink Correction
- Root cause of V33/V31A find failure:
  - $HOME/storage/downloads is a symlink.
  - Plain find does not reliably descend it.
  - Required command form:
      find -L "$HOME/storage/downloads" ...
- Locked contributor architecture:
  - Kotlin contributor remains as thin native adapter.
  - Native C owns engine computation/state.
  - Kotlin engine implementation deleted only after native live proof.
  - Kotlin fallback forbidden for migrated engines.
- VisionPreprocessor Kotlin blob fallback remains blocked until native_vision_blob.c replaces FrameScanner + ConnectedComponentEngine and is live-proven.
- Next gate:
  - Parse V33 with robust locator.
  - Parse V31A live proof with robust locator.
  - Issue V34A only from proven contributor migration queue.
MD
fi

# ------------------------------------------------------------------
# V33 parser
# ------------------------------------------------------------------
if [ -n "$V33" ] && [ -f "$V33" ]; then
  QOUT="$OUT_DIR/Splendor_V34_Contributor_Migration_Queue_$(date +%Y%m%d_%H%M%S).txt"

  python3 - "$V33" > "$QOUT" 2>&1 <<'PY'
import sys
import re
from pathlib import Path

src = Path(sys.argv[1])
text = src.read_text(encoding="utf-8", errors="ignore")
lines = text.splitlines()

print("================ V34 CONTRIBUTOR MIGRATION QUEUE ================")
print("SOURCE:", src)
print("SIZE_BYTES:", src.stat().st_size)
print()

blocks = []
current = None

section_re = re.compile(r"^CONTRIBUTOR:\s+([A-Za-z_][A-Za-z0-9_]*)\s*$")
next_major_re = re.compile(r"^(ENGINE DECLARATION SEARCH|NATIVE BRIDGE CURRENT|EXECUTION BUS|REMAINING CAMERA)")

for line in lines:
    stripped = line.strip()

    if next_major_re.match(stripped):
        if current:
            blocks.append(current)
            current = None
        continue

    m = section_re.match(stripped)
    if m:
        if current:
            blocks.append(current)
        current = {
            "name": m.group(1),
            "file": "",
            "line": "",
            "body_end": "",
            "class": "",
            "engine_calls": [],
            "android_deps": [],
            "state_hits": [],
            "alloc_hits": [],
            "camera_hits": [],
            "raw": [],
        }
        continue

    if current is not None:
        current["raw"].append(line)

        if stripped.startswith("FILE:"):
            current["file"] = stripped[5:].strip()
        elif stripped.startswith("LINE:"):
            current["line"] = stripped[5:].strip()
        elif stripped.startswith("BODY_END_LINE:"):
            current["body_end"] = stripped[14:].strip()
        elif stripped.startswith("CLASS:"):
            current["class"] = stripped[6:].strip()

def extract_list(raw, title):
    out = []
    inside = False
    for line in raw:
        s = line.strip()
        if s == f"--- {title} ---":
            inside = True
            continue
        if inside and s.startswith("--- ") and s.endswith(" ---"):
            break
        if inside:
            if not s or s == "NONE":
                continue
            out.append(s)
    return out

for b in blocks:
    b["engine_calls"] = extract_list(b["raw"], "ENGINE CALLS")
    b["android_deps"] = extract_list(b["raw"], "ANDROID DEPS")
    b["state_hits"] = extract_list(b["raw"], "STATE HITS")
    b["alloc_hits"] = extract_list(b["raw"], "ALLOC/HOTPATH HITS")
    b["camera_hits"] = extract_list(b["raw"], "CAMERA HITS")

priority = {
    "PURE_NATIVE_COMPUTE_CANDIDATE": 0,
    "PURE_NATIVE_COMPUTE_CANDIDATE_WITH_CAMERA_NORMALIZATION": 1,
    "PURE_OR_NEAR_PURE_NATIVE_COMPUTE_CANDIDATE_WITH_ALLOC_REMOVAL": 2,
    "STATEFUL_NATIVE_CONTEXT_REQUIRED": 3,
    "ANDROID_BOUND_NEEDS_KOTLIN_SUBMISSION": 4,
    "UNKNOWN_NO_ENGINE_CALL_DETECTED": 5,
}

def rank(b):
    return (priority.get(b["class"], 99), b["name"])

blocks.sort(key=rank)

print("=== SUMMARY ===")
print(f"CONTRIBUTORS_PARSED: {len(blocks)}")
counts = {}
for b in blocks:
    counts[b["class"]] = counts.get(b["class"], 0) + 1
for cls, cnt in sorted(counts.items(), key=lambda x: (-x[1], x[0])):
    print(f"{cnt:03d}  {cls}")

print()
print("=== MIGRATION QUEUE ===")
print(f"{'RANK':>4}  {'PRIORITY':>8}  {'CLASS':<70}  {'CONTRIBUTOR':<42}  {'FILE_LINE'}")
for i, b in enumerate(blocks, 1):
    print(f"{i:>4}  {priority.get(b['class'], 99):>8}  {b['class']:<70}  {b['name']:<42}  {b['file']}:{b['line']}")

print()
print("=== TOP 12 CANDIDATES WITH EVIDENCE ===")
for i, b in enumerate(blocks[:12], 1):
    print()
    print("-" * 100)
    print(f"[{i}] {b['name']}")
    print("-" * 100)
    print("FILE:", b["file"])
    print("LINE:", b["line"])
    print("BODY_END_LINE:", b["body_end"])
    print("CLASS:", b["class"])
    print("ENGINE_CALLS:", ", ".join(b["engine_calls"]) or "NONE")
    print("ANDROID_DEPS:", ", ".join(b["android_deps"]) or "NONE")
    print("STATE_HITS:", ", ".join(b["state_hits"]) or "NONE")
    print("ALLOC_HITS:", ", ".join(b["alloc_hits"]) or "NONE")
    print("CAMERA_HITS:", ", ".join(b["camera_hits"]) or "NONE")

print()
print("=== FIRST SAFE NATIVE ADAPTER SHORTLIST ===")
safe = [
    b for b in blocks
    if b["class"] in {
        "PURE_NATIVE_COMPUTE_CANDIDATE",
        "PURE_NATIVE_COMPUTE_CANDIDATE_WITH_CAMERA_NORMALIZATION",
        "PURE_OR_NEAR_PURE_NATIVE_COMPUTE_CANDIDATE_WITH_ALLOC_REMOVAL",
    }
    and not b["android_deps"]
]
if not safe:
    print("NONE")
else:
    for i, b in enumerate(safe[:8], 1):
        print(
            f"{i}. {b['name']} | {b['class']} | "
            f"engine_calls={', '.join(b['engine_calls']) or 'NONE'} | "
            f"alloc={', '.join(b['alloc_hits']) or 'NONE'} | "
            f"camera={', '.join(b['camera_hits']) or 'NONE'}"
        )

print()
print("=== STATEFUL SHORTLIST ===")
stateful = [b for b in blocks if b["class"] == "STATEFUL_NATIVE_CONTEXT_REQUIRED"]
if not stateful:
    print("NONE")
else:
    for i, b in enumerate(stateful[:20], 1):
        print(
            f"{i}. {b['name']} | "
            f"state={', '.join(b['state_hits']) or 'NONE'} | "
            f"engine_calls={', '.join(b['engine_calls']) or 'NONE'}"
        )

print()
print("=== ANDROID BOUND SHORTLIST ===")
android_bound = [b for b in blocks if b["class"] == "ANDROID_BOUND_NEEDS_KOTLIN_SUBMISSION"]
if not android_bound:
    print("NONE")
else:
    for i, b in enumerate(android_bound[:20], 1):
        print(
            f"{i}. {b['name']} | "
            f"deps={', '.join(b['android_deps']) or 'NONE'} | "
            f"engine_calls={', '.join(b['engine_calls']) or 'NONE'}"
        )

print()
print("=== UNKNOWN / NO ENGINE CALL DETECTED ===")
unknown = [b for b in blocks if b["class"] == "UNKNOWN_NO_ENGINE_CALL_DETECTED"]
if not unknown:
    print("NONE")
else:
    for i, b in enumerate(unknown[:20], 1):
        print(f"{i}. {b['name']} | file={b['file']}:{b['line']}")
PY

  echo
  echo "V34 QUEUE EXPORT: $QOUT"
  ls -lh "$QOUT" 2>/dev/null || true
  wc -c "$QOUT" 2>/dev/null || true
  sha256sum "$QOUT" 2>/dev/null || true

  echo
  echo "--- V34 QUEUE FIRST 1600 LINES ---"
  sed -n '1,1600p' "$QOUT" 2>/dev/null || true
else
  echo
  echo "SKIP V34 QUEUE: V33 file missing"
fi

# ------------------------------------------------------------------
# V31A parser
# ------------------------------------------------------------------
if [ -n "$V31A" ] && [ -f "$V31A" ]; then
  TOUT="$OUT_DIR/Splendor_V31A_Truth_Summary_$(date +%Y%m%d_%H%M%S).txt"

  python3 - "$V31A" > "$TOUT" 2>&1 <<'PY'
import sys
from pathlib import Path

p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8", errors="ignore")

checks = [
    "AUTHORITY_NATIVE_ACTIVE",
    "AUTHORITY_NATIVE_UNAVAILABLE",
    "NATIVE_VISION_PREPROCESS_ACTIVE",
    "NATIVE_VISION_PREPROCESS_UNAVAILABLE",
    "NATIVE_TRUTH",
    "CAMERA_PROFILE",
    "DYNAMIC_WIDE",
    "Pure Kotlin VisionPreprocessor active (NDK TLS bypass)",
    "VisionPreprocessor Kotlin blob path active",
    "UnsatisfiedLinkError",
    "TLS symbol",
    "STATIC_TLS",
    "gwp_asan",
    "getThreadLocals",
    "GAMEPLAY_EVENT",
    "dispatch-accepted",
    "SMART_ASSIST_GATE",
    "routed :: source=SMART_ASSIST",
    "ObserveOnly",
    "Runtime healthy; gameplay contributors own execution",
    "LOOP_FROZEN",
    "COLLECT_ZERO",
    "COLLECT_STALL",
    "REGISTRY_EMPTY",
    "REGISTRY_GAP",
    "ZERO_DISPATCH",
]

print("================ V31A TRUTH SUMMARY ================")
print("FILE:", p)
print("SIZE_BYTES:", p.stat().st_size)
print()

for c in checks:
    count = text.count(c)
    status = "PRESENT" if count else "ABSENT"
    print(f"{status:7}  {count:5}  {c}")

print()
print("=== DECISIVE LINES ===")
for line in text.splitlines():
    if any(c in line for c in checks):
        print(line[:500])
PY

  echo
  echo "V31A TRUTH SUMMARY: $TOUT"
  ls -lh "$TOUT" 2>/dev/null || true
  wc -c "$TOUT" 2>/dev/null || true
  sha256sum "$TOUT" 2>/dev/null || true

  echo
  echo "--- V31A TRUTH SUMMARY FIRST 400 LINES ---"
  sed -n '1,400p' "$TOUT" 2>/dev/null || true
else
  echo
  echo "SKIP V31A TRUTH: V31A/live proof file missing"
fi

echo
echo "================ V34 LOCATOR COMPLETE ================"

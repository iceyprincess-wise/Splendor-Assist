#!/usr/bin/env python3
import sys
import re
import shutil
import datetime
from pathlib import Path

ROOT = Path.cwd().resolve()
NB = ROOT / "app/src/main/java/com/assistant/NativeBridge.kt"

BEGIN = "// SPLENDOR_V36A_NATIVE_LOAD_TRUTH_BEGIN"
END = "// SPLENDOR_V36A_NATIVE_LOAD_TRUTH_END"


def fail(msg: str) -> None:
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


if not NB.is_file():
    fail(f"missing file: {NB}")

stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
bk = ROOT / ".backups" / f"v36a_native_load_truth_{stamp}"
bk.mkdir(parents=True, exist_ok=True)

dst = bk / NB.relative_to(ROOT)
dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(NB, dst)

print(f"BACKUP: {bk}")

text = NB.read_text(encoding="utf-8", errors="ignore")

if "object NativeBridge" not in text:
    fail("NativeBridge.kt does not contain object NativeBridge")

if re.search(r"\bval\s+nativeLoaded\b", text):
    fail("nativeLoaded is declared as val; manual conversion required before patch")

# Ensure RuntimeLogger import.
if "import com.assistant.diagnostic.RuntimeLogger" not in text:
    lines = text.splitlines()
    pkg_idx = None
    for i, line in enumerate(lines):
        if line.startswith("package "):
            pkg_idx = i
            break
    if pkg_idx is None:
        lines.insert(0, "import com.assistant.diagnostic.RuntimeLogger")
    else:
        lines.insert(pkg_idx + 1, "import com.assistant.diagnostic.RuntimeLogger")
    text = "\n".join(lines) + "\n"
    print("PATCHED: NativeBridge RuntimeLogger import")

# Ensure markNativeLoaded exists.
if not re.search(r"fun\s+markNativeLoaded\s*\(", text):
    m = re.search(r"(?m)^object\s+NativeBridge\b", text)
    if not m:
        fail("object NativeBridge declaration not found")

    open_idx = text.find("{", m.start())
    if open_idx == -1:
        fail("NativeBridge opening brace not found")

    depth = 0
    close_idx = -1
    in_str = False
    in_char = False
    esc = False
    in_line_comment = False
    in_block_comment = False

    i = open_idx
    n = len(text)
    while i < n:
        c = text[i]

        if c == "\n":
            in_line_comment = False

        if in_line_comment:
            i += 1
            continue

        if in_block_comment:
            if c == "*" and i + 1 < n and text[i + 1] == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue

        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end == -1:
                fail("unterminated raw string in brace matcher")
            i = end + 3
            continue

        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue

        if in_char:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == "'":
                in_char = False
            i += 1
            continue

        if c == "/" and i + 1 < n and text[i + 1] == "/":
            in_line_comment = True
            i += 2
            continue

        if c == "/" and i + 1 < n and text[i + 1] == "*":
            in_block_comment = True
            i += 2
            continue

        if c == '"':
            in_str = True
            esc = False
            i += 1
            continue

        if c == "'":
            in_char = True
            esc = False
            i += 1
            continue

        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                close_idx = i
                break

        i += 1

    if close_idx == -1:
        fail("NativeBridge closing brace not found")

    insert = """
    @Volatile
    var nativeLoaded: Boolean = false
        private set

    @JvmStatic
    fun markNativeLoaded() {
        nativeLoaded = true
        try {
            com.assistant.diagnostic.RuntimeLogger.log("NATIVE_BRIDGE_LOADED", "NATIVE_TRUTH")
        } catch (_: Throwable) {
        }
    }
"""

    # Only insert nativeLoaded var if absent.
    if not re.search(r"var\s+nativeLoaded\s*:\s*Boolean", text):
        text = text[:close_idx] + insert + text[close_idx:]
        print("PATCHED: NativeBridge nativeLoaded var + markNativeLoaded function")
    else:
        fn_insert = """
    @JvmStatic
    fun markNativeLoaded() {
        nativeLoaded = true
        try {
            com.assistant.diagnostic.RuntimeLogger.log("NATIVE_BRIDGE_LOADED", "NATIVE_TRUTH")
        } catch (_: Throwable) {
        }
    }
"""
        text = text[:close_idx] + fn_insert + text[close_idx:]
        print("PATCHED: NativeBridge markNativeLoaded function")

# Line-level loadLibrary patch.
lines = text.splitlines()

load_idx = None
load_re = re.compile(r"System\.loadLibrary\s*\(\s*(?:LIBRARY|\"splendor_native\")\s*\)")
for i, line in enumerate(lines):
    if load_re.search(line):
        load_idx = i
        break

if load_idx is None:
    fail("NativeBridge.kt anchor not found: System.loadLibrary(LIBRARY) or System.loadLibrary(\"splendor_native\")")

near = "\n".join(lines[load_idx:min(len(lines), load_idx + 10)])
if "markNativeLoaded()" not in near:
    ind = lines[load_idx][:len(lines[load_idx]) - len(lines[load_idx].lstrip())]
    lines[load_idx + 1:load_idx + 1] = [
        ind + BEGIN,
        ind + "markNativeLoaded()",
        ind + END,
    ]
    print("PATCHED: NativeBridge load-success markNativeLoaded call")
else:
    print("SKIP: NativeBridge load-success markNativeLoaded call already present")

# Catch failure patch.
catch_idx = None
catch_param = None
catch_re = re.compile(
    r"^\s*\}\s*catch\s*\(\s*([A-Za-z_][A-Za-z0-9_]*|_)\s*:\s*Throwable\s*\)\s*\{\s*$"
)

for j in range(load_idx, min(len(lines), load_idx + 120)):
    m = catch_re.match(lines[j])
    if m:
        catch_idx = j
        catch_param = m.group(1)
        break

if catch_idx is None:
    print("WARNING: no Throwable catch found near loadLibrary; load-failure mutation not patched")
else:
    block = "\n".join(lines[catch_idx:min(len(lines), catch_idx + 30)])
    if "nativeLoaded = false" not in block:
        if catch_param == "_":
            lines[catch_idx] = lines[catch_idx].replace(
                "catch (_: Throwable)",
                "catch (splendorLoadError: Throwable)",
            )
            catch_param = "splendorLoadError"

        ind = lines[catch_idx][:len(lines[catch_idx]) - len(lines[catch_idx].lstrip())] + "    "
        inserts = []

        joined = "\n".join(lines)
        if re.search(r"var\s+nativeLoaded\s*:\s*Boolean", joined):
            inserts.append(ind + "nativeLoaded = false")

        if re.search(r"var\s+nativePreprocessAvailable\s*:\s*Boolean", joined):
            inserts.append(ind + "nativePreprocessAvailable = false")

        if re.search(r"var\s+nativeAuthorityAvailable\s*:\s*Boolean", joined):
            inserts.append(ind + "nativeAuthorityAvailable = false")

        if re.search(r"var\s+nativeLastError\s*:\s*String\?", joined):
            inserts.append(ind + f"nativeLastError = {catch_param}.toString()")

        if inserts:
            lines[catch_idx + 1:catch_idx + 1] = inserts
            print("PATCHED: NativeBridge load-failure truth mutation")
        else:
            print("WARNING: load-failure catch found but no truth vars available to mutate")
    else:
        print("SKIP: NativeBridge load-failure truth mutation already present")

text = "\n".join(lines) + "\n"
NB.write_text(text, encoding="utf-8")

# Validation.
after = NB.read_text(encoding="utf-8", errors="ignore")
after_lines = after.splitlines()

load_idx2 = None
for i, line in enumerate(after_lines):
    if load_re.search(line):
        load_idx2 = i
        break

if load_idx2 is None:
    fail("post-validation: loadLibrary anchor missing")

near2 = "\n".join(after_lines[load_idx2:min(len(after_lines), load_idx2 + 10)])
if "markNativeLoaded()" not in near2:
    fail("post-validation: markNativeLoaded() not inserted after loadLibrary")

if not re.search(r"fun\s+markNativeLoaded\s*\(", after):
    fail("post-validation: markNativeLoaded function missing")

if not re.search(r"var\s+nativeLoaded\s*:\s*Boolean", after):
    fail("post-validation: nativeLoaded var missing")

catch_idx2 = None
for j in range(load_idx2, min(len(after_lines), load_idx2 + 120)):
    if catch_re.match(after_lines[j]):
        catch_idx2 = j
        break

if catch_idx2 is not None:
    block2 = "\n".join(after_lines[catch_idx2:min(len(after_lines), catch_idx2 + 30)])
    if "nativeLoaded = false" not in block2:
        fail("post-validation: load-failure nativeLoaded=false missing in catch")

print("VALIDATOR PASS: V36A NativeBridge load-truth patch structurally intact")

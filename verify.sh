#!/usr/bin/env bash
# verify.sh —  verification for the Splendor-Assist debug APK.
set -euo pipefail

export TMPDIR="${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
APK="app/build/outputs/apk/debug/app-debug.apk"
VERIFY_DIR="$TMPDIR/apk_verify"

rm -rf "$VERIFY_DIR"
mkdir -p "$VERIFY_DIR"

if [ ! -f "$APK" ]; then
  echo "FATAL: APK not found: $APK"
  exit 1
fi

echo "=== EXTRACTING AND VERIFYING .SO DIRECTLY FROM APK ==="
# Guarded — if the entry is absent, unzip fails and this FATAL runs.
# (Un guarded, `set -e` would kill the script here with no message at all.)
if ! unzip -o "$APK" "lib/arm64-v8a/libsplendor_native.so" -d "$VERIFY_DIR" >/dev/null 2>&1; then
  echo "FATAL: libsplendor_native.so is MISSING from the APK."
  exit 1
fi
SO="$VERIFY_DIR/lib/arm64-v8a/libsplendor_native.so"

if [ ! -f "$SO" ]; then
  echo "FATAL: libsplendor_native.so is MISSING from the APK."
  exit 1
fi

echo "SUCCESS: libsplendor_native.so is packaged inside the APK."

echo "=== FULL SOURCE NATIVE BRIDGE DECLARATIONS ==="
SOURCE_DECL_FILE="$VERIFY_DIR/source_native_declarations.txt"
DECL_NAMES_FILE="$VERIFY_DIR/declared_native_names.txt"
DECL_ALL_FILE="$VERIFY_DIR/declared_external_names.txt"
# Discover modules from settings.gradle.kts instead of hardcoding them.
MODULES="$(sed -nE 's/^[[:space:]]*include\(":(.+)"\).*/\1/p' settings.gradle.kts 2>/dev/null | tr '\n' ' ' || true)"
if [ -z "${MODULES// }" ]; then
  MODULES="app core"
fi
SRC_DIRS=""
for m in $MODULES; do
  if [ -d "$m/src" ]; then
    SRC_DIRS="$SRC_DIRS $m/src"
  fi
done
if [ -z "${SRC_DIRS// }" ]; then
  echo "FATAL: No module source dirs found (modules: $MODULES)."
  exit 1
fi
set +o pipefail
grep -RnE 'external fun [A-Za-z0-9_]+' $SRC_DIRS 2>/dev/null | tee "$SOURCE_DECL_FILE" || true
grep -RhoE 'external fun [A-Za-z0-9_]+' $SRC_DIRS 2>/dev/null | sed 's/external fun //' | sort -u > "$DECL_ALL_FILE" || true
set -o pipefail
grep -E '^native' "$DECL_ALL_FILE" > "$DECL_NAMES_FILE" || true
SOURCE_DECL_COUNT="$(wc -l < "$DECL_NAMES_FILE" | tr -d ' ')"
DECL_TOTAL_COUNT="$(wc -l < "$DECL_ALL_FILE" | tr -d ' ')"
echo "Source external declarations found: ${DECL_TOTAL_COUNT:-0} (native*: ${SOURCE_DECL_COUNT:-0})"
echo "SOURCE DECLARATION LIST SAVED: $SOURCE_DECL_FILE"
# Zero declarations is a failure, never a PROVEN.
if [ "${DECL_TOTAL_COUNT:-0}" -eq 0 ]; then
  echo "FATAL: No external fun declarations found in $SRC_DIRS."
  exit 1
fi

echo "=== FULL NATIVE JNI SYMBOL LIST FROM APK .SO ==="
SYMBOL_FILE="$VERIFY_DIR/native_jni_symbols.txt"
SYMBOL_NAMES_FILE="$VERIFY_DIR/jni_symbol_names.txt"
READELF_BIN="${READELF:-readelf}"

if ! command -v "$READELF_BIN" >/dev/null 2>&1; then
  READELF_BIN="llvm-readelf"
fi
# A missing tool must report itself, not masquerade as missing symbols.
if ! command -v "$READELF_BIN" >/dev/null 2>&1; then
  echo "FATAL: Neither readelf nor llvm-readelf is available (READELF='$READELF_BIN')."
  exit 1
fi

set +o pipefail
"$READELF_BIN" -Ws "$SO" | grep -E 'FUNC[[:space:]]+GLOBAL[[:space:]]+DEFAULT.*Java_com_assistant_' | tee "$SYMBOL_FILE" || true
set -o pipefail
awk '{print $NF}' "$SYMBOL_FILE" | sort -u > "$SYMBOL_NAMES_FILE" || true

NATIVE_JNI_COUNT="$(wc -l < "$SYMBOL_FILE" | tr -d ' ')"
echo "Packaged assistant JNI symbols found in APK .so: ${NATIVE_JNI_COUNT:-0}"
echo "JNI SYMBOL LIST SAVED: $SYMBOL_FILE"
echo "INFO: Source declarations vs packaged JNI symbols: ${SOURCE_DECL_COUNT:-0}/${NATIVE_JNI_COUNT:-0}"

if [ "${NATIVE_JNI_COUNT:-0}" -eq 0 ]; then
  echo "FATAL: No NativeBridge JNI symbols found in APK .so."
  exit 1
fi

# NOTE: never use `grep -q` on a pipe in this script. Under `set -o pipefail`
# the producer (unzip/readelf) dies of SIGPIPE when grep -q exits on the first
# match, and the pipeline then fails even though the match existed — that bug
# made the libc++ check FATAL a packaged library. Use plain grep to /dev/null
# (reads the whole stream) or grep -F for fixed strings like libc++_shared.so.
echo "=== .SO ARCHITECTURE / RUNTIME CHECKS ==="
if ! "$READELF_BIN" -h "$SO" | grep 'AArch64' >/dev/null; then
  echo "FATAL: libsplendor_native.so is not AArch64."
  exit 1
fi
echo "SUCCESS: .so architecture is AArch64."

if "$READELF_BIN" -Ws "$SO" | grep ' JNI_OnLoad$' >/dev/null; then
  echo "SUCCESS: JNI_OnLoad is exported."
else
  echo "WARNING: JNI_OnLoad is not exported (static JNI name lookup still applies)."
fi

if "$READELF_BIN" -d "$SO" | grep -F 'libc++_shared.so' >/dev/null; then
  if unzip -l "$APK" | grep -F 'lib/arm64-v8a/libc++_shared.so' >/dev/null; then
    echo "SUCCESS: libc++_shared.so is packaged."
  else
    echo "FATAL: .so needs libc++_shared.so but it is not packaged in the APK."
    exit 1
  fi
fi

OTHER_ABIS="$(unzip -l "$APK" | grep -oE 'lib/[A-Za-z0-9_.-]+/' | sort -u | grep -v 'lib/arm64-v8a/' || true)"
if [ -n "$OTHER_ABIS" ]; then
  echo "WARNING: APK contains lib dirs for unexpected ABIs:"
  printf '%s\n' "$OTHER_ABIS"
fi

echo "=== DECLARED NATIVES vs PACKAGED JNI SYMBOLS (SET CHECK) ==="
# Compare the two sets instead of only checking that the count is not 0.
MISSING_FILE="$VERIFY_DIR/missing_native_implementations.txt"
ORPHAN_FILE="$VERIFY_DIR/orphan_jni_exports.txt"
: > "$MISSING_FILE"
: > "$ORPHAN_FILE"
MISSING_COUNT=0
while IFS= read -r name; do
  [ -z "$name" ] && continue
  # JNI mangles '_' in a method name as '_1'.
  mangled="$(printf '%s' "$name" | sed 's/_/_1/g')"
  if ! grep -qE "_${mangled}$" "$SYMBOL_NAMES_FILE"; then
    printf '%s\n' "$name" >> "$MISSING_FILE"
    MISSING_COUNT=$((MISSING_COUNT + 1))
  fi
done < "$DECL_ALL_FILE"
ORPHAN_COUNT=0
while IFS= read -r sym; do
  [ -z "$sym" ] && continue
  # Bare method name: the segment after the last underscore. Exact for the
  # method names in this repo (none contains an underscore of its own).
  method="$(printf '%s' "$sym" | sed -E 's/.*_([A-Za-z0-9]+)$/\1/')"
  if ! grep -qxF "$method" "$DECL_ALL_FILE"; then
    printf '%s (%s)\n' "$method" "$sym" >> "$ORPHAN_FILE"
    ORPHAN_COUNT=$((ORPHAN_COUNT + 1))
  fi
done < "$SYMBOL_NAMES_FILE"
if [ "$MISSING_COUNT" -gt 0 ]; then
  echo "FATAL: $MISSING_COUNT declared native(s) have no packaged JNI implementation:"
  cat "$MISSING_FILE"
  exit 1
fi
echo "SUCCESS: Every declared native has a packaged JNI implementation (${DECL_TOTAL_COUNT}/${DECL_TOTAL_COUNT})."
if [ "$ORPHAN_COUNT" -gt 0 ]; then
  echo "WARNING: $ORPHAN_COUNT packaged JNI export(s) have no Kotlin declaration (dead export?):"
  cat "$ORPHAN_FILE"
  echo "Orphan list saved: $ORPHAN_FILE"
fi

echo "=== APK VERSION VERIFICATION ==="
# Termux paths first, /opt/android-sdk last (it does not exist on Termux).
TERMUX_AAPT2="$(grep -oE 'splendor\.termuxAapt2[[:space:]]*=[[:space:]]*[^[:space:]]+' gradle.properties 2>/dev/null | cut -d= -f2- | tr -d ' ' || true)"
badging_dump() {
  if command -v aapt2 >/dev/null 2>&1; then
    aapt2 dump badging "$1" 2>/dev/null
  elif command -v aapt >/dev/null 2>&1; then
    aapt dump badging "$1" 2>/dev/null
  elif [ -f "$HOME/bin/aapt2" ]; then
    "$HOME/bin/aapt2" dump badging "$1" 2>/dev/null
  elif [ -n "${TERMUX_AAPT2:-}" ] && [ -f "$TERMUX_AAPT2" ]; then
    "$TERMUX_AAPT2" dump badging "$1" 2>/dev/null
  elif [ -f "${ANDROID_HOME:-}/build-tools/34.0.0/aapt2" ]; then
    "${ANDROID_HOME}/build-tools/34.0.0/aapt2" dump badging "$1" 2>/dev/null
  elif [ -f "$HOME/android-sdk/build-tools/34.0.0/aapt2" ]; then
    "$HOME/android-sdk/build-tools/34.0.0/aapt2" dump badging "$1" 2>/dev/null
  elif ls "$HOME"/android-sdk/build-tools/*/aapt2 >/dev/null 2>&1; then
    "$(ls -d "$HOME"/android-sdk/build-tools/*/aapt2 | tail -n 1)" dump badging "$1" 2>/dev/null
  elif [ -f "/opt/android-sdk/build-tools/34.0.0/aapt2" ]; then
    "/opt/android-sdk/build-tools/34.0.0/aapt2" dump badging "$1" 2>/dev/null
  elif ls /opt/android-sdk/build-tools/*/aapt2 >/dev/null 2>&1; then
    "$(ls -d /opt/android-sdk/build-tools/*/aapt2 | tail -n 1)" dump badging "$1" 2>/dev/null
  else
    return 1
  fi
}

set +e
BADGING="$(badging_dump "$APK")"
BADGING_STATUS=$?
set -e

if [ "$BADGING_STATUS" -ne 0 ] || [ -z "$BADGING" ]; then
  echo "FATAL: aapt2/aapt badging dump failed."
  exit 1
fi

APK_PACKAGE="$(printf '%s\n' "$BADGING" | grep -oE "name='[^']+'" | head -1 | cut -d"'" -f2 || true)"
APK_VERSION_CODE="$(printf '%s\n' "$BADGING" | grep -oE "versionCode='[^']+'" | head -1 | grep -oE '[0-9]+' || true)"
APK_VERSION_NAME="$(printf '%s\n' "$BADGING" | grep -oE "versionName='[^']+'" | head -1 | cut -d"'" -f2 || true)"

SOURCE_VERSION_CODE="$(grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' app/build.gradle.kts 2>/dev/null | grep -oE '[0-9]+' | head -1 || true)"
SOURCE_VERSION_NAME="$(grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' app/build.gradle.kts 2>/dev/null | cut -d'"' -f2 || true)"

echo "APK package: $APK_PACKAGE"
echo "APK versionCode: $APK_VERSION_CODE"
echo "APK versionName: $APK_VERSION_NAME"
echo "SOURCE versionCode: $SOURCE_VERSION_CODE"
echo "SOURCE versionName: $SOURCE_VERSION_NAME"

if [ -z "$APK_VERSION_CODE" ] || [ -z "$SOURCE_VERSION_CODE" ]; then
  echo "FATAL: APK or SOURCE versionCode is empty."
  exit 1
fi

if [ -z "$APK_VERSION_NAME" ] || [ -z "$SOURCE_VERSION_NAME" ]; then
  echo "FATAL: APK or SOURCE versionName is empty."
  exit 1
fi

if [ "$APK_VERSION_CODE" != "$SOURCE_VERSION_CODE" ]; then
  echo "FATAL: APK versionCode does not match source."
  exit 1
fi

if [ "$APK_VERSION_NAME" != "$SOURCE_VERSION_NAME" ]; then
  echo "FATAL: APK versionName does not match source."
  exit 1
fi

echo "SUCCESS: APK version matches source."

echo "=== KOTLIN COMPILER OUTPUT / DEX FULL COVERAGE ==="
# The Python below reads DEX straight from the APK zip.
python3 - "$APK" "$VERIFY_DIR" <<'PY'
import pathlib
import re
import struct
import sys
import zipfile

apk = pathlib.Path(sys.argv[1])
verify_dir = pathlib.Path(sys.argv[2])

def read_uleb128(data, offset):
    value = 0
    shift = 0

    while True:
        b = data[offset]
        offset += 1
        value |= (b & 0x7F) << shift

        if (b & 0x80) == 0:
            return value, offset

        shift += 7

def dex_defined_classes(data):
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", data, 64)
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 96)

    string_offsets = [
        struct.unpack_from("<I", data, string_ids_off + (i * 4))[0]
        for i in range(string_ids_size)
    ]

    type_string_indexes = [
        struct.unpack_from("<I", data, type_ids_off + (i * 4))[0]
        for i in range(type_ids_size)
    ]

    classes = set()

    for i in range(class_defs_size):
        class_idx = struct.unpack_from("<I", data, class_defs_off + (i * 32))[0]
        string_idx = type_string_indexes[class_idx]
        string_offset = string_offsets[string_idx]

        _, pos = read_uleb128(data, string_offset)

        end = data.find(b"\x00", pos)
        if end < 0:
            continue

        descriptor = data[pos:end].decode("utf-8", "replace")

        if descriptor.startswith("L") and descriptor.endswith(";"):
            classes.add(descriptor[1:-1])

    return classes

# Discover modules from settings.gradle.kts instead of hardcoding them.
settings = pathlib.Path("settings.gradle.kts")
modules = []
if settings.exists():
    modules = re.findall(r'include\(":(.+?)"\)', settings.read_text(encoding="utf-8"))
if not modules:
    modules = ["app", "core"]

expected = set()

for module in modules:
    build_root = pathlib.Path(module) / "build"

    if not build_root.exists():
        continue

    for root in build_root.glob("**/kotlin-classes/debug"):
        if not root.is_dir():
            continue

        for class_file in root.rglob("*.class"):
            relative = class_file.relative_to(root)
            class_name = str(relative.with_suffix("")).replace("\\", "/")

            if class_name:
                expected.add(class_name)

if not expected:
    print("FATAL: No Kotlin compiler .class output found.")
    print("Expected kotlin-classes/debug outputs for modules:", ", ".join(modules))
    sys.exit(1)

found = set()
dex_files = []

with zipfile.ZipFile(apk, "r") as z:
    dex_files = sorted(
        name
        for name in z.namelist()
        if name == "classes.dex"
        or (
            name.startswith("classes")
            and name.endswith(".dex")
            and name[7:-4].isdigit()
        )
    )

    if not dex_files:
        print("FATAL: No DEX files found inside APK.")
        sys.exit(1)

    for dex_name in dex_files:
        found.update(dex_defined_classes(z.read(dex_name)))

missing = sorted(expected - found)
matched = sorted(expected & found)
all_dex_classes = sorted(found)

sep = "=" * 60

expected_file = verify_dir / "expected_kotlin_compiler_classes.txt"
matched_file = verify_dir / "matched_kotlin_compiler_classes.txt"
missing_file = verify_dir / "missing_kotlin_compiler_classes.txt"
all_dex_file = verify_dir / "all_dex_defined_classes.txt"

expected_file.write_text(
    "\n".join(sorted(expected)) + "\n",
    encoding="utf-8"
)

matched_file.write_text(
    "\n".join(matched) + ("\n" if matched else ""),
    encoding="utf-8"
)

missing_file.write_text(
    "\n".join(missing) + ("\n" if missing else ""),
    encoding="utf-8"
)

all_dex_file.write_text(
    "\n".join(all_dex_classes) + "\n",
    encoding="utf-8"
)

print(sep)
print("KOTLIN COMPILER CLASSFILES:", len(expected))
print("APK DEX DEFINED CLASSES INDEXED:", len(found))
print("MATCHED KOTLIN COMPILER CLASSES:", len(matched))
print("MISSING KOTLIN COMPILER CLASSES:", len(missing))
print(sep)

print("=== FULL MATCHED KOTLIN COMPILER CLASSES PRESENT IN APK DEX ===")
for index, name in enumerate(matched, start=1):
    print(f"{index:04d}: {name}")
print(sep)

print("MATCHED CLASS LIST SAVED:", matched_file)
print("ALL DEX CLASS LIST SAVED:", all_dex_file)
print(sep)

if missing:
    print("=== FULL MISSING KOTLIN COMPILER CLASSES ===")
    for index, name in enumerate(missing, start=1):
        print(f"{index:04d}: {name}")
    print(sep)
    print("MISSING CLASS LIST SAVED:", missing_file)
    print(f"FATAL: {len(missing)} actual compiler classes are missing from APK DEX.")
    sys.exit(1)

print("PROVEN: Every Kotlin compiler-generated class is defined in APK DEX.")
print("DEX FILES VERIFIED:", len(dex_files))
print("KOTLIN CLASSES CONFIRMED PRESENT:", len(matched))
PY

echo "SUCCESS: Kotlin/Dex full coverage verification passed."

echo "=== APK BINARY IDENTITY ==="
sha256sum "$APK"
ls -lh "$APK"

echo "=== VERIFICATION ARTIFACTS ==="
ls -lh "$VERIFY_DIR"

echo "=== VERIFICATION SUMMARY ==="
echo "Native declarations (external fun): ${DECL_TOTAL_COUNT} (native*: ${SOURCE_DECL_COUNT})"
echo "Packaged JNI symbols: ${NATIVE_JNI_COUNT}"
echo "Missing natives: ${MISSING_COUNT}"
echo "Orphan JNI exports (warning only): ${ORPHAN_COUNT}"
echo "APK: ${APK_PACKAGE} ${APK_VERSION_NAME} (${APK_VERSION_CODE})"
echo "SUCCESS: All verification checks passed."

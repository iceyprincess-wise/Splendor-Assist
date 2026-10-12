#!/usr/bin/env python3
import os
import shutil
import sys

def main():
    print("=== Splendor-Assist Patch Script ===")
    root = os.getcwd()
    print(f"Working Directory: {root}")

    # 1. Update verify.sh
    verify_sh = os.path.join(root, "verify.sh")
    if not os.path.exists(verify_sh):
        print(f"FAIL: {verify_sh} does not exist.")
        sys.exit(1)

    with open(verify_sh, "r", encoding="utf-8") as f:
        content = f.read()

    target_grep = 'NATIVE_JNI_COUNT="$(grep -Ec "Java_com_assistant_" "Native" "NativeBridge" "native" "nativeBridge" "$SYMBOL_FILE" 2>/dev/null || true)"'
    replacement_grep = 'NATIVE_JNI_COUNT="$(grep -Ec "Java_com_assistant_" "$SYMBOL_FILE" 2>/dev/null || true)"'

    target_badging = '''badging_dump() {
  if command -v aapt2 >/dev/null 2>&1; then
    aapt2 dump badging "$1" 2>/dev/null
  elif command -v aapt >/dev/null 2>&1; then
    aapt dump badging "$1" 2>/dev/null
  else
    return 1
  fi
}'''

    replacement_badging = '''badging_dump() {
  if command -v aapt2 >/dev/null 2>&1; then
    aapt2 dump badging "$1" 2>/dev/null
  elif command -v aapt >/dev/null 2>&1; then
    aapt dump badging "$1" 2>/dev/null
  elif [ -f "${ANDROID_HOME:-/opt/android-sdk}/build-tools/34.0.0/aapt2" ]; then
    "${ANDROID_HOME:-/opt/android-sdk}/build-tools/34.0.0/aapt2" dump badging "$1" 2>/dev/null
  elif ls /opt/android-sdk/build-tools/*/aapt2 >/dev/null 2>&1; then
    "$(ls -d /opt/android-sdk/build-tools/*/aapt2 | tail -n 1)" dump badging "$1" 2>/dev/null
  else
    return 1
  fi
}'''

    if target_grep in content:
        content = content.replace(target_grep, replacement_grep)
        print("PASS: Replaced NATIVE_JNI_COUNT calculation in verify.sh")
    elif replacement_grep in content:
        print("PASS: NATIVE_JNI_COUNT calculation already updated in verify.sh")
    else:
        print("FAIL: Could not locate target NATIVE_JNI_COUNT pattern in verify.sh")
        sys.exit(1)

    if target_badging in content:
        content = content.replace(target_badging, replacement_badging)
        print("PASS: Replaced badging_dump in verify.sh")
    elif replacement_badging in content:
        print("PASS: badging_dump already updated in verify.sh")
    else:
        print("FAIL: Could not locate target badging_dump pattern in verify.sh")
        sys.exit(1)

    with open(verify_sh, "w", encoding="utf-8") as f:
        f.write(content)

    # 2. Delete redundant mutation directory
    mutation_dir = os.path.join(root, "app", "src", "main", "cpp", "mutation")
    if os.path.exists(mutation_dir):
        shutil.rmtree(mutation_dir)
        print(f"PASS: Deleted redundant mutation directory: {mutation_dir}")
    else:
        print(f"PASS: Mutation directory does not exist or already removed: {mutation_dir}")

    # 3. Delete stale libsplendor_native.so in work/
    stale_so = os.path.join(root, "work", "libsplendor_native.so")
    if os.path.exists(stale_so):
        os.remove(stale_so)
        print(f"PASS: Deleted stale .so artifact: {stale_so}")
    else:
        print(f"PASS: Stale .so artifact does not exist or already removed: {stale_so}")

    print("ALL MUTATIONS COMPLETED SUCCESSFULLY.")

if __name__ == "__main__":
    main()

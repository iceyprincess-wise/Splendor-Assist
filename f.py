#!/usr/bin/env python3
"""
fix_escape.py — Correct Kotlin string interpolation corruption in gameplay_engine.kt
====================================================================================
Removes erroneous literal backslashes introduced by previous patch script.
"""

import sys
import os

TARGET_FILE = "app/src/main/java/com/assistant/gameplay_engine.kt"

REPLACEMENTS = [
    (
        'shouldLog("COLLECT_ZERO", "engines=\\$engines cycles=0 age=\\${ageMs / 1000}s")',
        'shouldLog("COLLECT_ZERO", "engines=$engines cycles=0 age=${ageMs / 1000}s")'
    ),
    (
        'shouldLog("REGISTRY_GAP", "engines=\\$engines")',
        'shouldLog("REGISTRY_GAP", "engines=$engines")'
    ),
    (
        'detected = "Only \\$engines/39 contributors registered. Missing \\${39 - engines}. " +',
        'detected = "Only $engines/39 contributors registered. Missing ${39 - engines}. " +'
    )
]

def main():
    if not os.path.exists(TARGET_FILE):
        print(f"BLOCKED: {TARGET_FILE} not found. Ensure you are in the repo root.")
        sys.exit(1)

    with open(TARGET_FILE, "r", encoding="utf-8") as f:
        src = f.read()

    modified = False
    for old, new in REPLACEMENTS:
        if old in src:
            src = src.replace(old, new)
            print(f"  FIXED: Removed erroneous backslash in {TARGET_FILE}")
            modified = True
        elif new in src:
            print(f"  ALREADY CORRECT: {old.split('(')[1].split(',')[0]} is properly formatted.")
        else:
            print(f"  WARN: Target string not found. Manual inspection required for: {old[:50]}...")

    if modified:
        with open(TARGET_FILE, "w", encoding="utf-8") as f:
            f.write(src)
        print("\n✅ FIX APPLIED. Ready for `push`.")
    else:
        print("\n✅ NO CORRUPTION DETECTED. File is clean.")

if __name__ == "__main__":
    main()

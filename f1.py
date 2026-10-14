#!/usr/bin/env python3
"""
Splendor-Assist Patch Script (f1.py)
Removes unused 'random' variable and ThreadLocalRandom import in PingEliminatorVpnService.kt
to fix Kotlin compiler warning.
"""

import sys
import os

def patch_vpn_service():
    path = "app/src/main/java/com/assistant/PingEliminatorVpnService.kt"
    if not os.path.exists(path):
        print(f"FAIL: File not found: {path}")
        sys.exit(1)

    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    import_target = "import java.util.concurrent.ThreadLocalRandom\n"
    var_target = "        val random = ThreadLocalRandom.current()\n"

    new_content = content
    if import_target in new_content:
        new_content = new_content.replace(import_target, "")
    if var_target in new_content:
        new_content = new_content.replace(var_target, "")

    if new_content == content:
        if "ThreadLocalRandom" not in content:
            print(f"PASS (already patched): {path}")
            return
        print(f"FAIL: Could not locate target patterns in {path}")
        sys.exit(1)

    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)

    # Verify mutation
    with open(path, "r", encoding="utf-8") as f:
        verified = f.read()

    if "ThreadLocalRandom" in verified:
        print(f"FAIL: ThreadLocalRandom still present in {path}")
        sys.exit(1)

    print(f"PASS: Cleaned unused random variable and import from {path}")

def main():
    print("============================================================")
    print("SPLENDOR-ASSIST PATCH EXECUTION (f1.py)")
    print("============================================================")
    patch_vpn_service()
    print("============================================================")
    print("RESULT: PATCH f1.py APPLIED SUCCESSFULLY")
    print("============================================================")

if __name__ == "__main__":
    main()

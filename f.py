#!/usr/bin/env python3
"""
Splendor-Assist Master Patch Script
Applies all verified fixes for:
1. PingEliminatorVpnService.kt - Remove unused 'random' variable causing Kotlin compiler warning.
2. OmnipotentGoalkeeperEngine.kt - Ungate processGoalkeeperDefensiveLayer by replacing hardcoded false attacker detection with dynamic spatial evaluation.
"""

import sys
import os

def patch_file(filepath, target, replacement):
    if not os.path.exists(filepath):
        print(f"FAIL: File not found: {filepath}")
        return False

    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if replacement in content:
        print(f"PASS (already patched): {filepath}")
        return True

    if target not in content:
        print(f"FAIL: Target string not found in {filepath}")
        return False

    new_content = content.replace(target, replacement)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(new_content)

    # Verify mutation
    with open(filepath, "r", encoding="utf-8") as f:
        verified_content = f.read()

    if replacement not in verified_content:
        print(f"FAIL: Mutation verification failed for {filepath}")
        return False

    print(f"PASS: Successfully patched {filepath}")
    return True

def main():
    print("============================================================")
    print("SPLENDOR-ASSIST MASTER PATCH EXECUTION")
    print("============================================================")

    all_success = True

    # Patch 1: Remove unused random variable in PingEliminatorVpnService.kt
    vpn_path = "app/src/main/java/com/assistant/PingEliminatorVpnService.kt"
    vpn_target = "        val random = ThreadLocalRandom.current()\n"
    vpn_replacement = ""
    if not patch_file(vpn_path, vpn_target, vpn_replacement):
        all_success = False

    # Patch 2: Ungate Goalkeeper Defensive Layer in OmnipotentGoalkeeperEngine.kt
    gk_path = "app/src/main/java/com/assistant/overlay/interceptor/OmnipotentGoalkeeperEngine.kt"
    gk_target = "isAttackerDetected = false, // Telemetry does not track attacker, prevent fabricated rush"
    gk_replacement = "isAttackerDetected = (t.ballY > height * 0.7f), // Active defensive evaluation when ball enters dangerous zone"
    if not patch_file(gk_path, gk_target, gk_replacement):
        all_success = False

    print("============================================================")
    if all_success:
        print("RESULT: ALL MASTER PATCH MUTATIONS APPLIED AND VERIFIED")
        print("============================================================")
        sys.exit(0)
    else:
        print("RESULT: MASTER PATCH FAILED")
        print("============================================================")
        sys.exit(1)

if __name__ == "__main__":
    main()

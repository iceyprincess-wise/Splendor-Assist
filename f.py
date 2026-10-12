#!/usr/bin/env python3
import sys
import os

def patch_omnipotent_gk_engine():
    path = "app/src/main/java/com/assistant/overlay/interceptor/OmnipotentGoalkeeperEngine.kt"
    if not os.path.exists(path):
        print(f"FAIL: {path} not found")
        sys.exit(1)

    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    target = "isAttackerDetected = false, // Telemetry does not track attacker, prevent fabricated rush"
    replacement = "isAttackerDetected = (t.ballY > height * 0.7f), // Active defensive evaluation when ball enters dangerous zone"

    if replacement in content:
        print(f"PASS (already patched): {path}")
        return

    if target not in content:
        print(f"FAIL: Target string not found in {path}")
        sys.exit(1)

    new_content = content.replace(target, replacement)
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)

    # Verify mutation
    with open(path, "r", encoding="utf-8") as f:
        verified_content = f.read()

    if replacement not in verified_content:
        print(f"FAIL: Verification failed for {path}")
        sys.exit(1)

    print(f"PASS: Patched {path}")

def main():
    print("=== STARTING PATCH APPLICATION ===")
    patch_omnipotent_gk_engine()
    print("=== ALL PATCHES APPLIED SUCCESSFULLY ===")

if __name__ == "__main__":
    main()

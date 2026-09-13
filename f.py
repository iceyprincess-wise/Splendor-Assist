#!/usr/bin/env python3
import os, sys, re, subprocess

EXPECTED_BAD_HEAD = "f5106fe936063a4ed859f0559f51a11b23c7d83e"
GOOD_HEAD = "c1d7de23fcadf962d106b10ec92ebd8d557a6b53"
REPO_PATH = os.path.expanduser("~/projects/Splendor-Assist")

def run_cmd(cmd, cwd=REPO_PATH):
    result = subprocess.run(cmd, shell=True, cwd=cwd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"CMD FAILED: {cmd}\n{result.stderr}")
        sys.exit(1)
    return result.stdout.strip()

print("=== SPLENDOR-ASSIST ROLLBACK & SAFE PATCH ===")

# 1. Verify we are on the broken commit
current_head = run_cmd("git rev-parse HEAD")
if not current_head.startswith(EXPECTED_BAD_HEAD):
    print(f"HEAD MISMATCH! Expected {EXPECTED_BAD_HEAD}, got {current_head}")
    sys.exit(1)
print(f"[OK] On broken commit: {current_head}")

# 2. Reset to the good commit
print("\n[ROLLBACK] Resetting to good commit...")
run_cmd(f"git reset --hard {GOOD_HEAD}")
print("[OK] Reset complete. All deleted engines and repositories restored.")

# 3. Apply ONLY the Watchdog fix
watchdog_file = os.path.join(REPO_PATH, "app/src/main/java/com/assistant/adapter/watchdog/WatchdogAdapterService.kt")
print("\n[PATCH] Fixing WatchdogAdapterService.kt stale adapterMap...")
with open(watchdog_file, "r") as f:
    watchdog_content = f.read()

old_map_pattern = r'private val adapterMap = mapOf\([\s\S]*?\)'
new_map = """private val adapterMap = mapOf(
        "performance_engine" to "com.assistant.PerformanceEngineService",
        "gameplay_engine"    to "com.assistant.GameplayEngineService"
    )"""

if re.search(old_map_pattern, watchdog_content):
    watchdog_content = re.sub(old_map_pattern, new_map, watchdog_content, count=1)
    with open(watchdog_file, "w") as f:
        f.write(watchdog_content)
    print("[OK] WatchdogAdapterService.kt patched.")
else:
    print("[WARN] Could not find adapterMap pattern.")

# 4. Structural validation
print("\n[VALIDATE] Running git diff --check...")
run_cmd("git diff --check")

print("\n=== PATCH COMPLETE ===")
print("Next steps:")
print("1. Run: ./gradlew assembleDebug")
print("2. Run: git add app/src/main/java/com/assistant/adapter/watchdog/WatchdogAdapterService.kt")
print("3. Run: git commit -m 'fix: correct watchdog recovery targets for consolidated domains'")
print("4. Run: git push origin main --force-with-lease")

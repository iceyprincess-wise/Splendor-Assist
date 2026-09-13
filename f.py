#!/usr/bin/env python3
import os, sys, subprocess

EXPECTED_HEAD = "eb7e2ee1f0aa6e5fa95ac6ee91e27520e767fa01"
REPO_PATH = os.path.expanduser("~/projects/Splendor-Assist")

def run_cmd(cmd, cwd=REPO_PATH):
    result = subprocess.run(cmd, shell=True, cwd=cwd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"CMD FAILED: {cmd}\n{result.stderr}")
        sys.exit(1)
    return result.stdout.strip()

print("=== SPLENDOR-ASSIST SURGICAL LINE-RANGE PURGE ===")

current_head = run_cmd("git rev-parse HEAD")
if not current_head.startswith(EXPECTED_HEAD):
    print(f"HEAD MISMATCH! Expected {EXPECTED_HEAD}, got {current_head}")
    sys.exit(1)
print(f"[OK] HEAD verified: {current_head}")

perf_file = os.path.join(REPO_PATH, "app/src/main/java/com/assistant/performance_engine.kt")
game_file = os.path.join(REPO_PATH, "app/src/main/java/com/assistant/gameplay_engine.kt")

# Exact 1-indexed inclusive line ranges verified via brace-matching AST parser
perf_deletions = [
    (73, 288),    # LagAdapterService
    (1127, 1199), # StutterAdapterService
    (1319, 1448), # PingAdapterService
    (1782, 1862), # NetAdapterService
    (2409, 2484), # InputAdapterService
    (3111, 3244), # LmkAdapterService
    (3589, 3773), # MemoryAdapterService
    (3876, 3983), # ThermalAdapterService
    (3985, 4134), # BatteryAdapterService
    (4136, 4235), # SyncAdapterService
    (4237, 4464), # BootAdapterService
    (4466, 4570), # SchedulerAdapterService
    (4584, 4845), # InterruptionAdapterService
    (4847, 4877), # InterruptionCoordinator
    (4879, 4897), # InterruptionRepository
    (4899, 4923)  # InterruptionState + TelephonyStateRepository
]

game_deletions = [
    (12269, 12387) # SmartAssistAdapterService
]

def purge_lines(file_path, ranges):
    with open(file_path, "r") as f:
        lines = f.readlines()
    
    original_count = len(lines)
    # Sort ranges descending to delete from bottom up without shifting indices
    ranges_sorted = sorted(ranges, key=lambda x: x[0], reverse=True)
    
    for start, end in ranges_sorted:
        # Convert 1-indexed inclusive to 0-indexed exclusive
        s = start - 1
        e = end
        if s >= 0 and e <= len(lines):
            del lines[s:e]
            print(f"  Deleted lines {start}-{end} ({end - start + 1} lines)")
        else:
            print(f"  OUT OF BOUNDS: {start}-{end}")
            
    with open(file_path, "w") as f:
        f.writelines(lines)
        
    print(f"  File reduced from {original_count} to {len(lines)} lines.")

print("\n[PATCH] Purging dead adapters from performance_engine.kt...")
purge_lines(perf_file, perf_deletions)

print("\n[PATCH] Purging dead SmartAssistAdapterService from gameplay_engine.kt...")
purge_lines(game_file, game_deletions)

print("\n[VALIDATE] Running git diff --check...")
run_cmd("git diff --check")

print("\n=== SURGICAL PURGE COMPLETE ===")
print("Next steps:")
print("1. Run: ./gradlew assembleDebug")
print("2. Run: git add app/src/main/java/com/assistant/performance_engine.kt app/src/main/java/com/assistant/gameplay_engine.kt")
print("3. Run: git commit -m 'refactor: surgical line-range purge of dead pre-consolidation adapters'")
print("4. Run: git push origin main")

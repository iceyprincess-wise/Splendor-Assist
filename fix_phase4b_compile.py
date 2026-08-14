#!/usr/bin/env python3
"""
PHASE 4B — COMPILE FIX
Fixes RuntimeSelfHealEngine.kt:387 (return L) and ShotContributor.kt:26 (unclosed if)
"""
import os

REPO = os.path.expanduser("~/projects/Splendor-Assist")

def read(p):
    with open(p, encoding="utf-8") as f: return f.read()

def write(p, c):
    with open(p, "w", encoding="utf-8") as f: f.write(c)

def patch(path, old, new, label):
    if not os.path.exists(path):
        print(f"  SKIP (file not found): {path}"); return False
    c = read(path)
    if old not in c:
        print(f"  SKIP (already fixed?): {label}"); return False
    write(path, c.replace(old, new, 1))
    print(f"  FIXED: {label}"); return True

print("=" * 60)
print("PHASE 4B — COMPILE FIX")
print("=" * 60)

print("\n[1] RuntimeSelfHealEngine.kt — 'return L' → 'return'")
patch(
    os.path.join(REPO,
        "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist",
        "RuntimeSelfHealEngine.kt"),
    '            val cycles = (snap["collectCycles"] as? Long) ?: return L',
    '            val cycles = (snap["collectCycles"] as? Long) ?: return',
    "RuntimeSelfHealEngine: return L → return"
)

print("\n[2] ShotContributor.kt — close if-condition before comment")
patch(
    os.path.join(REPO,
        "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/contributors",
        "ShotContributor.kt"),
    '        if (distToGoal > 680f  // PHASE4B: 30fps hybrid gives accurate goal detection farther out) return null',
    '        if (distToGoal > 680f) return null // PHASE4B: 30fps hybrid gives accurate goal detection farther out',
    "ShotContributor: fix unclosed if-condition"
)

print("\n" + "=" * 60)
print("DONE. Now run: bash bump_version.sh && ./gradlew :app:assembleDebug 2>&1 | tail -40")
print("=" * 60)

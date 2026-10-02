#!/usr/bin/env python3
"""
fix_missing.py — Apply missing mutation tools and remove AttackingVector gate.
================================================================================
Uses exact, verified anchors from SHA a8617f8e.
"""
import os

PATCHES = [
    # 1. Inject Thread Priority Escalation in OverlayService (Corrected Anchor)
    (
        "app/src/main/java/com/assistant/OverlayService.kt",
        [
            (
                "                com.assistant.AppContributorRegistration.ensureRegistered()\n                com.assistant.RuntimeCoordinator.reportCaptureReady()",
                "                com.assistant.AppContributorRegistration.ensureRegistered()\n                // MUTATION TOOL 1: Escalate thread priority to MAX (-19) for zero-delay execution\n                com.assistant.NativeBridge.nativeEscalateThreadPriority()\n                com.assistant.RuntimeCoordinator.reportCaptureReady()"
            )
        ]
    ),
    # 2. Remove restrictive range gate from AttackingVectorContributor (Corrected Target)
    (
        "app/src/main/java/com/assistant/gameplay_engine.kt",
        [
            (
                "    val dist=hypot((frame.ballX-gkX).toDouble(),(frame.ballY-gkY).toDouble()).toFloat()\n    if(dist>MAX_SHOT_RANGE)return null\n    val point=CriticalAttackingVectorEngine.computeAbsoluteScoringVector",
                "    val dist=hypot((frame.ballX-gkX).toDouble(),(frame.ballY-gkY).toDouble()).toFloat()\n    // UNCONDITIONAL AGGRESSIVE EXECUTION: Allow shot calculation at all ranges for recovery\n    val point=CriticalAttackingVectorEngine.computeAbsoluteScoringVector"
            )
        ]
    )
]

def apply_patch(path: str, replacements):
    if not os.path.exists(path):
        print(f"  ❌ SKIP: {path} (not found)")
        return
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    
    modified = False
    for old, new in replacements:
        if old in src:
            src = src.replace(old, new, 1)
            print(f"  ✅ PATCHED: {path}")
            modified = True
        elif new in src:
            print(f"  ⏭️  ALREADY: {path}")
        else:
            print(f"  ⚠️  WARN: Anchor not found in {path}")
            # Debug: print a snippet to help identify
            idx = src.find(old[:30])
            if idx != -1:
                print(f"     Context around match attempt:\n{src[max(0,idx-50):idx+150]}")
            
    if modified:
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)

def main():
    for path, reps in PATCHES:
        apply_patch(path, reps)
    print("\n✅ fix_missing.py execution complete.")

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
fix_clash.py — V42.1 JVM-signature clash fix
=============================================
Renames the @JvmStatic companion method from autoHealCapture() to
requestAutoHeal() so it no longer collides with the instance method
of the same name at the class level.

Run from your repo root:
    python3 fix_clash.py
"""
import sys, re

FILES = {
    "app/src/main/java/com/assistant/OverlayService.kt": [
        (
            # Old companion block – plain @JvmStatic with the colliding name
            "@JvmStatic\n        fun autoHealCapture(): Boolean =\n            instance?.autoHealCapture() ?: false",
            # New companion block – renamed + explanatory comment
            "// Named requestAutoHeal() (not autoHealCapture()) to avoid JVM signature\n        // which would collide with the instance fun autoHealCapture() at class level.\n        @JvmStatic\n        fun requestAutoHeal(): Boolean =\n            instance?.autoHealCapture() ?: false",
        ),
    ],
    "app/src/main/java/com/assistant/gameplay_engine.kt": [
        (
            "com.assistant.OverlayService.autoHealCapture()",
            "com.assistant.OverlayService.requestAutoHeal()",
        ),
    ],
    "app/src/main/java/com/assistant/SplendorCaptureRecovery.kt": [
        (
            "com.assistant.OverlayService.autoHealCapture()",
            "com.assistant.OverlayService.requestAutoHeal()",
        ),
    ],
}


def patch(path: str, replacements):
    try:
        with open(path, "r", encoding="utf-8") as f:
            src = f.read()
    except FileNotFoundError:
        print(f"  SKIP  {path}  (not found)")
        return False

    changed = False
    for old, new in replacements:
        if old in src:
            src = src.replace(old, new, 1)
            changed = True
            print(f"  PATCHED  {path}")
        elif new in src:
            print(f"  ALREADY  {path}  (already patched)")
        else:
            print(f"  WARN     {path}  — old text not found, not patched")
    if changed:
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
    return changed


def main():
    ok = True
    for path, reps in FILES.items():
        ok = patch(path, reps) or ok
    print("\nDone. Build with: ./gradlew assembleRelease")


if __name__ == "__main__":
    main()

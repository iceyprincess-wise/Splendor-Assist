#!/usr/bin/env python3
"""
f1.py — Engine-logic bug fixes (Splendor-Assist)
=================================================
Covers G1/G8 items from SPLENDOR_ENGINE_EFFECTIVENESS_QUEUE.md

PATCH A — PressEvadeContributor: lateral-axis mismatch
  Zone 0 = top-wing area (low Y), Zone 2 = bottom-wing (high Y).
  The escape target was computed with offsetX (depth movement) instead
  of offsetY (lateral/wing movement).  A contributor named "PressEvade"
  that evades pressure by moving depth-wise is incoherent; the fix
  makes it actually move the player TOWARD the clear wing.

PATCH B — RuntimeSelfHealEngine contributor-count thresholds
  EXPECTED_CONTRIBUTOR_COUNT was raised from 29 to 39 when nine new
  contributors were onboarded, but the COLLECT_ZERO and REGISTRY_GAP
  health checks inside gameplay_engine.kt still reference 29.  The
  self-heal engine was therefore silent about a 30–38 contributor gap
  and could never trigger re-registration for partial-onboarding faults.

Run from repo root:
    python3 f1.py
"""

import sys

PATCHES = [
    # ── PATCH A: PressEvadeContributor lateral-axis fix ─────────────────
    (
        "app/src/main/java/com/assistant/contributors/PressEvadeContributor.kt",
        [
            (
                # OLD: depth-axis escape using offsetX
                """\
        val offsetX = when (bestZone) {
            0 -> -LATERAL_STEP_PX
            2 -> LATERAL_STEP_PX
            else -> 0f
        }
        if (offsetX == 0f) return null // no lateral escape worth taking

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.EVADE,
            targetX = (frame.ballX + offsetX).coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),""",
                # NEW: lateral-axis escape using offsetY (toward the clear wing)
                """\
        // Zone 0 = top-wing strip (low Y), Zone 2 = bottom-wing strip (high Y).
        // Escape LATERALLY toward the clear wing, i.e. adjust Y not X.
        val offsetY = when (bestZone) {
            0 -> -LATERAL_STEP_PX  // clear top wing → move ball toward top wing
            2 -> LATERAL_STEP_PX   // clear bottom wing → move ball toward bottom wing
            else -> 0f
        }
        if (offsetY == 0f) return null // mid-strip balance – no clear wing to escape to

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.EVADE,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = (frame.ballY + offsetY).coerceAtLeast(0f),""",
            ),
        ],
    ),

    # ── PATCH B: self-heal count thresholds 29 → 39 ─────────────────────
    (
        "app/src/main/java/com/assistant/gameplay_engine.kt",
        [
            # B-1: COLLECT_ZERO gate — "engines >= 29" fires when 29+ registered;
            #       must be >= 39 so we only fire after all contributors loaded.
            (
                "if (assistEnabled && engines >= 29 && cycles == 0L && ageMs > 10_000L && shouldLog(\"COLLECT_ZERO\", \"engines=$engines cycles=0 age=${ageMs / 1000}s\")) {",
                "if (assistEnabled && engines >= 39 && cycles == 0L && ageMs > 10_000L && shouldLog(\"COLLECT_ZERO\", \"engines=\$engines cycles=0 age=\${ageMs / 1000}s\")) {",
            ),
            # B-2: REGISTRY_GAP gate — threshold and messages
            (
                "if (engines < 29 && warmed && shouldLog(\"REGISTRY_GAP\", \"engines=$engines\")) {",
                "if (engines < 39 && warmed && shouldLog(\"REGISTRY_GAP\", \"engines=\$engines\")) {",
            ),
            (
                'detected = "Only $engines/29 contributors registered. Missing ${29 - engines}. " +',
                'detected = "Only \$engines/39 contributors registered. Missing \${39 - engines}. " +',
            ),
        ],
    ),
]


def patch(path: str, replacements):
    try:
        with open(path, "r", encoding="utf-8") as f:
            src = f.read()
    except FileNotFoundError:
        print(f"  SKIP    {path}  (not found)")
        return

    for old, new in replacements:
        if old in src:
            src = src.replace(old, new, 1)
            print(f"  PATCHED {path}")
        elif new in src:
            print(f"  ALREADY {path}  (already patched)")
        else:
            print(f"  WARN    {path}  — old text not found; inspect manually")

    with open(path, "w", encoding="utf-8") as f:
        f.write(src)


def main():
    for path, reps in PATCHES:
        patch(path, reps)
    print("\nf1.py done.")


if __name__ == "__main__":
    main()

data class TrueShotResult(val targetX:Float,val targetY:Float,val authority:Float,val onTarget:Boolean)
// TrueShotEngine migrated to native_true_shot.c (V54)
/* ======
TrueShotEngine Anchor
====== */

/* ========
VisionTrust
======== */
/**
 * GAP 2 + GAP 3 — VISION TRUST
 *
 * GAP 1C  capture gating: only frames from the game are trusted at all.
 * GAP 2   age decay:      a sighting loses value as it gets old.
 * GAP 3   lane confidence: derived from trust + defender spread + motion stability.
 *
 * Self-contained on purpose. Nothing here reaches into an engine, so any engine
 * may read it without creating a dependency, and it can be nano-upgraded alone.
 *
 * V3 (Task B): every gate answers the admin store live (defaults = the old
 * hard-coded values). These gates decide whether the ENTIRE contributor
 * stack is allowed to act - on a slower device the fixed 180ms latency
 * ceiling and 400ms sighting window were chronically rejecting honest
 * frames with no way to tune them without a rebuild.
 */

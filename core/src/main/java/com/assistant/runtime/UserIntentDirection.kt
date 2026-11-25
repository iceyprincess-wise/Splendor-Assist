package com.assistant.runtime

/**
 * UserIntentDirection — the user's held joystick direction, read live
 * from the frame by JoystickKnobReader (interceptor package).
 *
 * dirX/dirY is a UNIT vector in capture-frame screen coords (x right,
 * y down) — the same space the dispatch swipe vectors live in, so the
 * RDL can compare it directly against a request's (end - start)
 * direction with a dot product. magnitude is 0..1 against the
 * measured max drag (DispatchBounds.maxDragPx, falling back to
 * min(w,h) x 0.08 while unmeasured) — 0 is at rest, 1 is full stick.
 * confidence is 0..1 (matched knob pixels vs the expected disc area
 * at the scan step); a reading is only published when confidence
 * clears the reader's gate, so a null on the frame means "no direction
 * known", never a guessed direction. Fail-open by construction.
 *
 * MEASURED GROUND TRUTH (parent-measured 2026-11-24 from Ed's
 * screenshots 837488_2_wpqw.jpg + 837487_3_mq0b.jpg, both 1600x720):
 * knob (207,207,207) neutral grey, disc radius 55-60 source px,
 * background black (0,0,0). The knob is at REST in both screenshots —
 * movement tracking is UNPROVEN until a deflected frame is measured
 * (see JoystickKnobReader).
 */
data class UserIntentDirection(
    val dirX: Float,
    val dirY: Float,
    val magnitude: Float,
    val confidence: Float
)

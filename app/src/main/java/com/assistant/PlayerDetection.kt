package com.assistant

class PlayerDetection(
    var x: Float,
    var y: Float,
    var confidence: Float,
    var isUserTeam: Boolean,
    // CURSOR-ID (Phase 5, step 1): true when this detection IS the
    // indicator ring (PlayerDetector's hollow-blue-blob keying) — i.e.
    // the game cursor player. Default false so every existing
    // positional construction keeps working; the flag is set only by
    // the ring branch in PlayerDetector.detect and carried through
    // NMS, association and the frame. A detection without the ring
    // never claims control.
    var isControlled: Boolean = false
)

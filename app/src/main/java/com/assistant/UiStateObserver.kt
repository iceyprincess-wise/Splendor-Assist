package com.assistant

import com.assistant.execution.UiExecutionGate

/**
 * OCR phrases are explicit visual evidence; missing OCR is never declared a popup.
 * SceneModeGate: the same phrase list also suppresses dispatch during replay /
 * halftime / full-time (not live play). Fail-open: an unknown scene never blocks.
 * Deliberately NOT gated: goal celebration ("goal" would false-positive on
 * "goal kick", which is live play), kickoff cutscene ("kick off" is ambiguous),
 * dead-ball (no dead-ball signal exists in RuntimeFrame or OCR).
 */
object UiStateObserver {
    fun observeText(text: String) {
        // G-C (batch 4, D12 zero-added-load): the same already-OCR'd text
        // is handed to the match-state parser — no new capture, no new
        // OCR pass. A score+minute pair updates the state FrameAssembler
        // copies into the frame; anything else leaves it untouched.
        try { MatchStateEngine.observe(text) } catch (_: Throwable) { }
        val normalized = text.lowercase(java.util.Locale.ROOT)
        val reason = listOf("reconnecting", "connection lost", "network error", "resume match", "match paused",
            // SceneModeGate: replay / halftime / full-time are not live play.
            // Same explicit-evidence discipline as the 5 above. "full time"
            // (not MatchPhase.FULLTIME) because minute >= 90 includes
            // stoppage-time live play; the literal phrase is match-over only.
            "replay", "half time", "full time")
            .firstOrNull { normalized.contains(it) } ?: return
        UiExecutionGate.block("ocr:$reason")
    }
}

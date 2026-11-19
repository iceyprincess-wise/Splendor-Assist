package com.assistant

import com.assistant.execution.UiExecutionGate

/** OCR phrases are explicit visual evidence; missing OCR is never declared a popup. */
object UiStateObserver {
    fun observeText(text: String) {
        // G-C (batch 4, D12 zero-added-load): the same already-OCR'd text
        // is handed to the match-state parser — no new capture, no new
        // OCR pass. A score+minute pair updates the state FrameAssembler
        // copies into the frame; anything else leaves it untouched.
        try { MatchStateEngine.observe(text) } catch (_: Throwable) { }
        val normalized = text.lowercase(java.util.Locale.ROOT)
        val reason = listOf("reconnecting", "connection lost", "network error", "resume match", "match paused")
            .firstOrNull { normalized.contains(it) } ?: return
        UiExecutionGate.block("ocr:$reason")
    }
}

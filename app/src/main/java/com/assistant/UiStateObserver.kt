package com.assistant

import com.assistant.execution.UiExecutionGate

/** OCR phrases are explicit visual evidence; missing OCR is never declared a popup. */
object UiStateObserver {
    fun observeText(text: String) {
        val normalized = text.lowercase(java.util.Locale.ROOT)
        val reason = listOf("reconnecting", "connection lost", "network error", "resume match", "match paused")
            .firstOrNull { normalized.contains(it) } ?: return
        UiExecutionGate.block("ocr:$reason")
    }
}

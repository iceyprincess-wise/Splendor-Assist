package com.assistant.adapter.lmk

object ViewInvalidationFilter {

    fun shouldInvalidate(
        source: String,
        critical: Boolean
    ): Boolean {

        ViewInvalidationRepository.record(
            ViewInvalidationEvent(
                source = source,
                critical = critical,
                timestamp = System.currentTimeMillis()
            )
        )

        return critical
    }
}

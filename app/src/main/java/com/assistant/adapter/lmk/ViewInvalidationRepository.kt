package com.assistant.adapter.lmk

object ViewInvalidationRepository {

    private val events =
        mutableListOf<ViewInvalidationEvent>()

    @Synchronized
    fun record(
        event: ViewInvalidationEvent
    ) {
        events.add(event)
    }

    @Synchronized
    fun getAll(): List<ViewInvalidationEvent> {
        return events.toList()
    }
}

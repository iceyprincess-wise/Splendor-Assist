package com.assistant.controlroom

enum class AdapterCategory {
    CORE,
    SMART_ASSIST,
    GOALKEEPER,
    INTERCEPTION,
    PERFORMANCE,
    FUTURE
}

data class AdapterControlRoom(
    val adapterId: String,
    val displayName: String,
    val category: AdapterCategory,
    val enabled: Boolean = true,
    val intensity: Int = 50
)

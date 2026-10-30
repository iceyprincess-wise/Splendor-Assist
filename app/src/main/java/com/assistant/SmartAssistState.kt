data class SmartAssistState(
    val enabled: Boolean = true,
    val panicMode: Boolean = false,
    val configuration: SmartAssistConfiguration = SmartAssistConfiguration(),
    val lastUpdated: Long = System.currentTimeMillis()
)


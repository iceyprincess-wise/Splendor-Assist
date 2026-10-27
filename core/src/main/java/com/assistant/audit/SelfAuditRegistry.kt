package com.assistant.audit

object SelfAuditRegistry {

    @Volatile
    private var state = "UNVERIFIED"

    @Volatile
    private var verifiedNodes = 0

    @Volatile
    private var totalNodes = 0

    private val nodeStates =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    @Synchronized
    fun update(
        node: String,
        status: String
    ) {
        nodeStates[node] = status
    }

    @Synchronized
    fun snapshot(): String {

        return buildString {

            append("Audit : ")
            append(state)

            append("\nVerified : ")
            append(verifiedNodes)

            append("/")

            append(totalNodes)

            if (nodeStates.isNotEmpty()) {

                append("\n\n")

                nodeStates.forEach {
                    append(it.key)
                    append(" : ")
                    append(it.value)
                    append("\n")
                }
            }
        }
    }
}

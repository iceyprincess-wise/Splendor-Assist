package com.assistant.audit

object SelfAuditRegistry {

    @Volatile
    private var state = "UNVERIFIED"

    @Volatile
    private var verifiedNodes = 0

    @Volatile
    private var totalNodes = 0

    private val nodeStates =
        mutableMapOf<String,String>()

    @Synchronized
    fun update(
        verified: Int,
        total: Int
    ) {
        verifiedNodes = verified
        totalNodes = total

        state =
            if (
                verified >= total &&
                total > 0
            ) {
                "PASS"
            } else {
                "WARNING"
            }
    }

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

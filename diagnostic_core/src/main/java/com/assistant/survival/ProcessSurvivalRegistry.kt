package com.assistant.survival

object ProcessSurvivalRegistry {

    private val processes =
        mutableMapOf<String,String>()

    @Synchronized
    fun update(
        process: String,
        state: String
    ) {
        processes[process] = state
    }

    @Synchronized
    fun snapshot(): String {

        if (processes.isEmpty())
            return "PROCESS: NONE"

        return buildString {

            append("PROCESS\n")

            processes.forEach { (name,state) ->

                append(name)
                append(" : ")
                append(state)
                append("\n")
            }
        }
    }
}

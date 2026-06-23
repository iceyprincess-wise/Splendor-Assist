package com.assistant.controlroom

object ControlRoomBootstrap {

    fun initialize() {

        AdapterControlRoomRegistry.register(
            AdapterControlRoom(
                "smart_assist",
                "Smart Assist",
                AdapterCategory.SMART_ASSIST
            )
        )

        AdapterControlRoomRegistry.register(
            AdapterControlRoom(
                "goalkeeper",
                "Goalkeeper Engine",
                AdapterCategory.GOALKEEPER
            )
        )

        AdapterControlRoomRegistry.register(
            AdapterControlRoom(
                "interception",
                "Interception Engine",
                AdapterCategory.INTERCEPTION
            )
        )
    }
}

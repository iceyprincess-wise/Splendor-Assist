package com.assistant.controlroom

import java.util.concurrent.ConcurrentHashMap

object AdapterControlRoomRegistry {

    private val rooms =
        ConcurrentHashMap<String, AdapterControlRoom>()

    fun register(
        room: AdapterControlRoom
    ) {
        rooms[room.adapterId] = room
    }

    fun get(
        adapterId: String
    ): AdapterControlRoom? =
        rooms[adapterId]

    fun getAll():
        List<AdapterControlRoom> =
        rooms.values.sortedBy {
            it.displayName
        }

    fun setEnabled(
        adapterId: String,
        enabled: Boolean
    ) {

        val room =
            rooms[adapterId]
                ?: return

        rooms[adapterId] =
            room.copy(
                enabled = enabled
            )
    }

    fun setIntensity(
        adapterId: String,
        intensity: Int
    ) {

        val room =
            rooms[adapterId]
                ?: return

        rooms[adapterId] =
            room.copy(
                intensity =
                    intensity.coerceIn(
                        0,
                        100
                    )
            )
    }
}

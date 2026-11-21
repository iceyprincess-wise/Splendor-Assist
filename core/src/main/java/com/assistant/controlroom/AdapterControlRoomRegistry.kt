package com.assistant.controlroom

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of the control rooms, keyed by adapter id.
 *
 * Population (verified at HEAD 598ed68e): ControlRoomBootstrap registers
 * exactly three rooms at App.onCreate in the main process —
 * "smart_assist", "goalkeeper", "interception" — before
 * AdapterControlRoomSync.start runs. Writers after that are only
 * AdapterControlRoomSync (setEnabled/setIntensity mirroring live runtime
 * state). Readers are only MainActivity.refreshRoomBulbs: `get` for the
 * goalkeeper/interception `enabled` bulbs, `getAll().size` for the
 * future-rooms bulb. No control-room UI Activity reads this registry.
 *
 * Read census (Letter A item 37 — the sentence below this paragraph
 * was stale and is corrected here): of the five stored fields, `enabled`
 * and `intensity` are read by MainActivity.refreshRoomBulbs
 * (MainActivity.kt:287/296/305 read `intensity` for the bulb labels,
 * :291/300 read `enabled`), and the room count via `getAll().size`.
 * `displayName` is read only as a `getAll()` sort key, and `category`
 * is now the primary `getAll()` sort key (category, then displayName),
 * so every stored field has a live reader. The old claim that
 * "`category` and `intensity` are never read back anywhere" was false
 * for intensity (MainActivity reads it) and is now false for category
 * too (the sort below reads it on every getAll call).
 *
 * Contract:
 * - Intensity invariant: every stored room has intensity in
 *   [AdapterControlRoom.MIN_INTENSITY, AdapterControlRoom.MAX_INTENSITY].
 *   Both write paths clamp on entry; the data class itself never throws.
 * - No silent no-op: setEnabled/setIntensity return false and log
 *   (tag CONTROL_ROOM) when the id has no room, instead of returning
 *   silently as before. Callers that ignore the result still compile.
 * - Per-key atomic updates: setEnabled/setIntensity apply via
 *   ConcurrentHashMap.compute, so two concurrent updates to the same room
 *   cannot lose one another (the old get-then-put pair was not atomic).
 * - Re-register is a merge, not a reset: if the id already exists the
 *   live `enabled`/`intensity` are kept and only `displayName`/`category`
 *   are adopted, so a second ControlRoomBootstrap.initialize() can never
 *   silently wipe synced live state back to the defaults. This mirrors
 *   the freshest-wins merge in AdapterHealthRegistry.restore (D31-2).
 * - No unregister/remove/clear on purpose: the set is the three fixed
 *   bootstrap rooms for the process lifetime, nothing ever unregisters,
 *   and an uncalled remove() would itself be dead code.
 *
 * `getAll()` returns a fresh list sorted by category, then displayName, on every call
 * (a snapshot; later writes do not mutate a returned list).
 */
object AdapterControlRoomRegistry {

    private val rooms =
        ConcurrentHashMap<String, AdapterControlRoom>()

    fun register(
        room: AdapterControlRoom
    ) {
        val clampedIntensity =
            room.intensity.coerceIn(
                AdapterControlRoom.MIN_INTENSITY,
                AdapterControlRoom.MAX_INTENSITY
            )
        if (clampedIntensity != room.intensity) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "register: intensity ${room.intensity} for adapterId=${room.adapterId} clamped to $clampedIntensity",
                "CONTROL_ROOM"
            )
        }

        // Log-only pre-check: the merge below is atomic, this flag only
        // decides whether a diagnostic line is emitted.
        val alreadyRegistered =
            rooms.containsKey(room.adapterId)

        rooms.compute(
            room.adapterId
        ) { _, existing ->
            if (existing == null) {
                room.copy(
                    intensity = clampedIntensity
                )
            } else {
                // Keep the live state; adopt only the new identity.
                existing.copy(
                    displayName = room.displayName,
                    category = room.category
                )
            }
        }

        if (alreadyRegistered) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "register: adapterId=${room.adapterId} already registered; kept live enabled/intensity, updated displayName/category",
                "CONTROL_ROOM"
            )
        }
    }

    fun get(
        adapterId: String
    ): AdapterControlRoom? =
        rooms[adapterId]

    // Letter A item 37: `category` is the primary sort key (then
    // displayName) — the live reader for the category field. Rooms group
    // by category in every getAll() result (AdapterControlRoomSync.snapshot
    // -> MainActivity.refreshRoomBulbs); enum order is declaration order.
    fun getAll():
        List<AdapterControlRoom> =
        rooms.values.sortedWith(compareBy({ it.category }, { it.displayName }))

    /**
     * Returns true if a room with this id existed and was updated,
     * false if the id is unknown (no room registered under it) — the
     * call is then a logged no-op, never a silent one.
     */
    fun setEnabled(
        adapterId: String,
        enabled: Boolean
    ): Boolean {

        val updated =
            rooms.compute(
                adapterId
            ) { _, room ->
                room?.copy(
                    enabled = enabled
                )
            }

        if (updated == null) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "setEnabled: unknown adapterId=$adapterId (no room registered; call ignored)",
                "CONTROL_ROOM"
            )
            return false
        }

        return true
    }

    /**
     * Returns true if a room with this id existed and was updated,
     * false if the id is unknown (no room registered under it) — the
     * call is then a logged no-op, never a silent one. The value is
     * clamped into the registry intensity range on entry.
     */
    fun setIntensity(
        adapterId: String,
        intensity: Int
    ): Boolean {

        val clampedIntensity =
            intensity.coerceIn(
                AdapterControlRoom.MIN_INTENSITY,
                AdapterControlRoom.MAX_INTENSITY
            )

        val updated =
            rooms.compute(
                adapterId
            ) { _, room ->
                room?.copy(
                    intensity = clampedIntensity
                )
            }

        if (updated == null) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "setIntensity: unknown adapterId=$adapterId (no room registered; call ignored)",
                "CONTROL_ROOM"
            )
            return false
        }

        return true
    }
}

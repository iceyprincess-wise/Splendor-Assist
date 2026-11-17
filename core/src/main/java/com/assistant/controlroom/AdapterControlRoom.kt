package com.assistant.controlroom

/**
 * Category a control room belongs to.
 *
 * Liveness at HEAD 598ed68e (verified by repo-wide grep):
 * SMART_ASSIST, GOALKEEPER and INTERCEPTION are the only values ever
 * constructed — one room each, registered by ControlRoomBootstrap.
 * CORE, PERFORMANCE and FUTURE have no room registered anywhere and no
 * `when` over this enum exists, so they are reserved for future rooms,
 * not dead branches to delete: FutureRoomsActivity lists planned rooms
 * but does not use these values yet. Do not remove a value to "clean
 * up" — re-adding it later is the same edit in reverse, and nothing
 * proves a future room will not need it.
 */
enum class AdapterCategory {
    CORE,
    SMART_ASSIST,
    GOALKEEPER,
    INTERCEPTION,
    PERFORMANCE,
    FUTURE
}

/**
 * One adapter's control-room card: identity plus its live state.
 *
 * `intensity` is a 0..100 scale. The invariant is enforced by
 * [AdapterControlRoomRegistry] on entry — [AdapterControlRoomRegistry.register]
 * and [AdapterControlRoomRegistry.setIntensity] both clamp into range —
 * never by a throwing `init` check here: a bad value on the App startup
 * path must degrade to a clamp, not crash `App.onCreate`.
 *
 * Defaults are the bootstrap values: ControlRoomBootstrap constructs all
 * three rooms with only (adapterId, displayName, category), so every room
 * starts enabled with intensity 50 until AdapterControlRoomSync overwrites
 * both fields from live runtime state at startup (App.onCreate, main
 * process, immediately after bootstrap).
 *
 * All properties are `val`, so a stored room can never be mutated in
 * place by a reader holding it; the registry replaces the whole room
 * with `copy()` on every write.
 */
data class AdapterControlRoom(
    val adapterId: String,
    val displayName: String,
    val category: AdapterCategory,
    val enabled: Boolean = true,
    val intensity: Int = 50
) {

    companion object {

        /** Single source of truth for the intensity range; the registry clamps to these. */
        const val MIN_INTENSITY = 0

        /** Single source of truth for the intensity range; the registry clamps to these. */
        const val MAX_INTENSITY = 100
    }
}
